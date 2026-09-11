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

import com.j4mb.payment_orchestrator.payments.application.command.VoidPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayOperation;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
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

class VoidPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static Payment authorizedPayment(PaymentId id) {
        Payment payment = Payment.rehydrate(
                id,
                ProviderType.STRIPE,
                new ProviderReference("pi_123"),
                usd("100.00"),
                usd("0.00"),
                usd("0.00"),
                PaymentStatus.AUTHORIZED,
                new IdempotencyKey("original-key-" + id),
                null,
                NOW,
                NOW);
        payment.pullDomainEvents();
        return payment;
    }

    private static VoidPaymentCommand command(PaymentId paymentId) {
        return new VoidPaymentCommand(paymentId, new IdempotencyKey("key-" + System.nanoTime()), "customer_cancelled");
    }

    private PaymentRepositoryPort paymentRepository;
    private PaymentGatewayResolver gatewayResolver;
    private IdempotencyCheckService idempotencyCheck;
    private DomainEventPublisherPort eventPublisher;
    private PaymentGatewayPort gateway;
    private VoidPaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepositoryPort.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        idempotencyCheck = mock(IdempotencyCheckService.class);
        eventPublisher = mock(DomainEventPublisherPort.class);
        gateway = mock(PaymentGatewayPort.class);
        service = new VoidPaymentService(paymentRepository, gatewayResolver, idempotencyCheck, eventPublisher, CLOCK);

        when(gatewayResolver.resolve(any())).thenReturn(gateway);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.empty());
    }

    @Nested
    class FreshRequest {

        @Test
        void authorizedPayment_releasesTheAuthorizationAndPublishesEvents() {
            PaymentId id = PaymentId.newId();
            Payment payment = authorizedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.voidAuthorization(any(), eq("customer_cancelled")))
                    .thenReturn(GatewayOperation.succeeded("voided"));
            VoidPaymentCommand cmd = command(id);

            PaymentResult result = service.voidPayment(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.VOIDED);
            verify(idempotencyCheck).complete(eq(cmd.idempotencyKey()), any());
            verify(eventPublisher).publishAll(argThat(events -> !events.isEmpty()));
        }

        @Test
        void paymentIdNotFound_abandonsTheKeyAndThrowsNotFound() {
            PaymentId id = PaymentId.newId();
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());
            VoidPaymentCommand cmd = command(id);

            assertThatThrownBy(() -> service.voidPayment(cmd)).isInstanceOf(PaymentNotFoundException.class);

            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
        }

        @Test
        void paymentNotInAVoidableStatus_abandonsTheKeyWithoutCallingTheGateway() {
            PaymentId id = PaymentId.newId();
            Payment alreadyCaptured = Payment.rehydrate(
                    id,
                    ProviderType.STRIPE,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("100.00"),
                    usd("0.00"),
                    PaymentStatus.CAPTURED,
                    new IdempotencyKey("original-key-" + id),
                    null,
                    NOW,
                    NOW);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(alreadyCaptured));
            VoidPaymentCommand cmd = command(id);

            assertThatThrownBy(() -> service.voidPayment(cmd)).isInstanceOf(InvalidPaymentStateTransitionException.class);

            verifyNoInteractions(gateway);
            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void declinedByProvider_abandonsTheKeyWithoutPersisting() {
            PaymentId id = PaymentId.newId();
            Payment payment = authorizedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.voidAuthorization(any(), any())).thenReturn(GatewayOperation.failed("rejected", "already_settled"));
            VoidPaymentCommand cmd = command(id);

            assertThatThrownBy(() -> service.voidPayment(cmd))
                    .isInstanceOf(PaymentDeclinedException.class)
                    .hasMessageContaining("already_settled");

            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void gatewayUnreachable_abandonsTheKeyAndPropagatesWithoutPersisting() {
            PaymentId id = PaymentId.newId();
            Payment payment = authorizedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.voidAuthorization(any(), any())).thenThrow(new IllegalStateException("mock gateway unreachable"));
            VoidPaymentCommand cmd = command(id);

            assertThatThrownBy(() -> service.voidPayment(cmd)).isInstanceOf(IllegalStateException.class);

            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            verify(paymentRepository, never()).save(any());
        }
    }

    @Nested
    class ReplayedRequest {

        @Test
        void previouslyCompletedVoid_returnsItWithoutCallingTheGatewayAgain() {
            PaymentId id = PaymentId.newId();
            Payment voided = Payment.rehydrate(
                    id,
                    ProviderType.STRIPE,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("0.00"),
                    usd("0.00"),
                    PaymentStatus.VOIDED,
                    new IdempotencyKey("original-key-" + id),
                    null,
                    NOW,
                    NOW);
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.of(voided));
            VoidPaymentCommand cmd = command(id);

            PaymentResult result = service.voidPayment(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.VOIDED);
            verifyNoInteractions(gatewayResolver, gateway);
            verify(paymentRepository, never()).findByIdForUpdate(any());
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void claimedIdMissingFromRepository_throwsNotFound() {
            PaymentId id = PaymentId.newId();
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.empty());
            VoidPaymentCommand cmd = command(id);

            assertThatThrownBy(() -> service.voidPayment(cmd)).isInstanceOf(PaymentNotFoundException.class);
        }
    }
}
