package com.j4mb.payment_orchestrator.payments.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.j4mb.payment_orchestrator.payments.application.command.AuthorizePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.command.AuthorizePaymentCommand.CaptureMode;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayAuthorization;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AuthorizePaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static AuthorizePaymentCommand command(CaptureMode mode) {
        return new AuthorizePaymentCommand(
                ProviderType.STRIPE,
                usd("100.00"),
                new IdempotencyKey("key-" + System.nanoTime()),
                mode,
                "tok_visa",
                "test payment");
    }

    private PaymentRepositoryPort paymentRepository;
    private PaymentGatewayResolver gatewayResolver;
    private IdempotencyCheckService idempotencyCheck;
    private DomainEventPublisherPort eventPublisher;
    private PaymentGatewayPort gateway;
    private AuthorizePaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepositoryPort.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        idempotencyCheck = mock(IdempotencyCheckService.class);
        eventPublisher = mock(DomainEventPublisherPort.class);
        gateway = mock(PaymentGatewayPort.class);
        service = new AuthorizePaymentService(paymentRepository, gatewayResolver, idempotencyCheck, eventPublisher, CLOCK);

        when(gatewayResolver.resolve(any())).thenReturn(gateway);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    class FreshRequest {

        @BeforeEach
        void freshKey() {
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.empty());
        }

        @Test
        void manualCapture_authorizesWithoutCapturingAndPublishesEvents() {
            AuthorizePaymentCommand cmd = command(CaptureMode.MANUAL);
            ProviderReference reference = new ProviderReference("pi_123");
            when(gateway.authorize(any(), eq("tok_visa"), eq(CaptureMode.MANUAL)))
                    .thenReturn(GatewayAuthorization.authorized(reference, "requires_capture"));

            PaymentResult result = service.authorize(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.providerReference()).isEqualTo(reference.toString());
            assertThat(result.capturedAmount()).isEqualTo(usd("0.00"));
            verify(idempotencyCheck).complete(eq(cmd.idempotencyKey()), any());
            verify(eventPublisher).publishAll(argThat(events -> !events.isEmpty()));
        }

        @Test
        void automaticCapture_authorizesAndCapturesInOneStep() {
            AuthorizePaymentCommand cmd = command(CaptureMode.AUTOMATIC);
            ProviderReference reference = new ProviderReference("pi_456");
            when(gateway.authorize(any(), eq("tok_visa"), eq(CaptureMode.AUTOMATIC)))
                    .thenReturn(GatewayAuthorization.captured(reference, "paid"));

            PaymentResult result = service.authorize(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.capturedAmount()).isEqualTo(cmd.amount());
        }

        @Test
        void declinedByProvider_persistsAFailedPaymentThenThrows() {
            AuthorizePaymentCommand cmd = command(CaptureMode.MANUAL);
            ProviderReference reference = new ProviderReference("pi_789");
            when(gateway.authorize(any(), any(), any()))
                    .thenReturn(GatewayAuthorization.declined(reference, "card_declined", "insufficient_funds"));

            assertThatThrownBy(() -> service.authorize(cmd))
                    .isInstanceOf(PaymentDeclinedException.class)
                    .hasMessageContaining("insufficient_funds");

            verify(paymentRepository).save(argThat(payment -> payment.status() == PaymentStatus.FAILED));
            verify(idempotencyCheck).complete(eq(cmd.idempotencyKey()), any());
            verify(idempotencyCheck, never()).abandon(any());
        }

        @Test
        void gatewayUnreachable_abandonsTheKeyAndPropagatesWithoutPersisting() {
            AuthorizePaymentCommand cmd = command(CaptureMode.MANUAL);
            when(gateway.authorize(any(), any(), any())).thenThrow(new IllegalStateException("mock gateway unreachable"));

            assertThatThrownBy(() -> service.authorize(cmd)).isInstanceOf(IllegalStateException.class);

            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            verify(paymentRepository, never()).save(any());
        }
    }

    @Nested
    class ReplayedRequest {

        @Test
        void previouslyFailedPayment_rethrowsTheOriginalDeclineWithoutCallingTheGateway() {
            AuthorizePaymentCommand cmd = command(CaptureMode.MANUAL);
            PaymentId id = PaymentId.newId();
            Payment failedPayment = Payment.rehydrate(
                    id,
                    cmd.provider(),
                    null,
                    cmd.amount(),
                    Money.zero(cmd.amount().currency()),
                    Money.zero(cmd.amount().currency()),
                    PaymentStatus.FAILED,
                    cmd.idempotencyKey(),
                    "card_declined",
                    NOW,
                    NOW);
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.of(failedPayment));

            assertThatThrownBy(() -> service.authorize(cmd))
                    .isInstanceOf(PaymentDeclinedException.class)
                    .hasMessageContaining("card_declined");

            verifyNoInteractions(gatewayResolver);
        }

        @Test
        void previouslySucceededPayment_returnsItWithoutCallingTheGatewayAgain() {
            AuthorizePaymentCommand cmd = command(CaptureMode.MANUAL);
            PaymentId id = PaymentId.newId();
            Payment authorizedPayment = Payment.rehydrate(
                    id,
                    cmd.provider(),
                    new ProviderReference("pi_existing"),
                    cmd.amount(),
                    Money.zero(cmd.amount().currency()),
                    Money.zero(cmd.amount().currency()),
                    PaymentStatus.AUTHORIZED,
                    cmd.idempotencyKey(),
                    null,
                    NOW,
                    NOW);
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.of(authorizedPayment));

            PaymentResult result = service.authorize(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            verifyNoInteractions(gatewayResolver);
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void claimedIdMissingFromRepository_throwsNotFound() {
            AuthorizePaymentCommand cmd = command(CaptureMode.MANUAL);
            PaymentId id = PaymentId.newId();
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.authorize(cmd)).isInstanceOf(PaymentNotFoundException.class);
        }
    }
}
