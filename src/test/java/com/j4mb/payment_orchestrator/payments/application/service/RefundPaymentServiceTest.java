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

import com.j4mb.payment_orchestrator.payments.application.command.RefundPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayOperation;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.service.RefundPolicy;
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

class RefundPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static Payment capturedPayment(PaymentId id) {
        Payment payment = Payment.rehydrate(
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
        payment.pullDomainEvents();
        return payment;
    }

    private static RefundPaymentCommand command(PaymentId paymentId, Optional<Money> amount) {
        return new RefundPaymentCommand(
                paymentId, amount, new IdempotencyKey("key-" + System.nanoTime()), "customer_request");
    }

    private PaymentRepositoryPort paymentRepository;
    private PaymentGatewayResolver gatewayResolver;
    private IdempotencyCheckService idempotencyCheck;
    private DomainEventPublisherPort eventPublisher;
    private PaymentGatewayPort gateway;
    private RefundPaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepositoryPort.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        idempotencyCheck = mock(IdempotencyCheckService.class);
        eventPublisher = mock(DomainEventPublisherPort.class);
        gateway = mock(PaymentGatewayPort.class);
        service = new RefundPaymentService(
                paymentRepository, gatewayResolver, idempotencyCheck, eventPublisher, new RefundPolicy(), CLOCK);

        when(gatewayResolver.resolve(any())).thenReturn(gateway);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.empty());
    }

    @Nested
    class FreshRequest {

        @Test
        void noAmountGiven_refundsTheFullCapturedBalance() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.refund(any(), eq(usd("100.00")), eq("customer_request")))
                    .thenReturn(GatewayOperation.succeeded("refunded"));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            PaymentResult result = service.refund(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(result.refundedAmount()).isEqualTo(usd("100.00"));
            verify(idempotencyCheck).complete(eq(cmd.idempotencyKey()), any());
            verify(eventPublisher).publishAll(argThat(events -> !events.isEmpty()));
        }

        @Test
        void partialAmountGiven_movesToPartiallyRefunded() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.refund(any(), eq(usd("30.00")), any())).thenReturn(GatewayOperation.succeeded("refunded"));
            RefundPaymentCommand cmd = command(id, Optional.of(usd("30.00")));

            PaymentResult result = service.refund(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(result.refundedAmount()).isEqualTo(usd("30.00"));
        }

        @Test
        void paymentIdNotFound_abandonsTheKeyAndThrowsNotFound() {
            PaymentId id = PaymentId.newId();
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());
            RefundPaymentCommand cmd = command(id, Optional.empty());

            assertThatThrownBy(() -> service.refund(cmd)).isInstanceOf(PaymentNotFoundException.class);

            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
        }

        @Test
        void amountExceedsRefundable_abandonsTheKeyWithoutCallingTheGateway() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            RefundPaymentCommand cmd = command(id, Optional.of(usd("150.00")));

            assertThatThrownBy(() -> service.refund(cmd)).isInstanceOf(InvalidRefundAmountException.class);

            verifyNoInteractions(gateway);
            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void paymentNotInARefundableStatus_abandonsTheKeyWithoutCallingTheGateway() {
            PaymentId id = PaymentId.newId();
            Payment authorizedOnly = Payment.rehydrate(
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
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(authorizedOnly));
            RefundPaymentCommand cmd = command(id, Optional.of(usd("10.00")));

            assertThatThrownBy(() -> service.refund(cmd)).isInstanceOf(InvalidPaymentStateTransitionException.class);

            verifyNoInteractions(gateway);
            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
        }

        @Test
        void declinedByProvider_abandonsTheKeyWithoutPersisting() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.refund(any(), any(), any())).thenReturn(GatewayOperation.failed("rejected", "already_refunded"));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            assertThatThrownBy(() -> service.refund(cmd))
                    .isInstanceOf(PaymentDeclinedException.class)
                    .hasMessageContaining("already_refunded");

            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void gatewayUnreachable_abandonsTheKeyAndPropagatesWithoutPersisting() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.refund(any(), any(), any())).thenThrow(new IllegalStateException("mock gateway unreachable"));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            assertThatThrownBy(() -> service.refund(cmd)).isInstanceOf(IllegalStateException.class);

            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            verify(paymentRepository, never()).save(any());
        }
    }

    @Nested
    class ReplayedRequest {

        @Test
        void previouslyCompletedRefund_returnsItWithoutCallingTheGatewayAgain() {
            PaymentId id = PaymentId.newId();
            Payment refunded = Payment.rehydrate(
                    id,
                    ProviderType.STRIPE,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("100.00"),
                    usd("100.00"),
                    PaymentStatus.REFUNDED,
                    new IdempotencyKey("original-key-" + id),
                    null,
                    NOW,
                    NOW);
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.of(refunded));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            PaymentResult result = service.refund(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
            verifyNoInteractions(gatewayResolver, gateway);
            verify(paymentRepository, never()).findByIdForUpdate(any());
            verify(paymentRepository, never()).save(any());
        }

        @Test
        void claimedIdMissingFromRepository_throwsNotFound() {
            PaymentId id = PaymentId.newId();
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.empty());
            RefundPaymentCommand cmd = command(id, Optional.empty());

            assertThatThrownBy(() -> service.refund(cmd)).isInstanceOf(PaymentNotFoundException.class);
        }
    }
}
