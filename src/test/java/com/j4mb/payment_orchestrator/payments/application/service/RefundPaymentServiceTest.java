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
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class RefundPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static Payment rehydrate(
            PaymentId id,
            ProviderReference reference,
            Money authorizedAmount,
            Money capturedAmount,
            Money refundedAmount,
            PaymentStatus status,
            IdempotencyKey key) {
        boolean refundPending = status == PaymentStatus.REFUND_PENDING;
        return Payment.rehydrate(
                id,
                ProviderType.STRIPE,
                reference,
                authorizedAmount,
                capturedAmount,
                refundedAmount,
                status,
                key,
                "tok_visa",
                CaptureMode.MANUAL,
                null,
                0,
                refundPending ? NOW : null,
                refundPending ? capturedAmount.minus(refundedAmount) : null,
                refundPending ? "customer_request" : null,
                refundPending ? "prior-attempt" : null,
                NOW,
                NOW);
    }

    private static Payment capturedPayment(PaymentId id) {
        Payment payment = rehydrate(
                id,
                new ProviderReference("pi_123"),
                usd("100.00"),
                usd("100.00"),
                usd("0.00"),
                PaymentStatus.CAPTURED,
                new IdempotencyKey("original-key-" + id));
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
    private PlatformTransactionManager transactionManager;
    private RefundPaymentService service;

    /**
     * Snapshots the status at each {@code save} call as it happens: {@code Payment} is mutable and
     * the mock echoes back the same reference every time, so asserting on a captured argument after
     * the fact would only ever see its final state, not what it was at each individual save.
     */
    private final java.util.List<PaymentStatus> savedStatuses = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepositoryPort.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        idempotencyCheck = mock(IdempotencyCheckService.class);
        eventPublisher = mock(DomainEventPublisherPort.class);
        gateway = mock(PaymentGatewayPort.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new RefundPaymentService(
                paymentRepository,
                gatewayResolver,
                idempotencyCheck,
                eventPublisher,
                new RefundPolicy(),
                CLOCK,
                transactionManager);

        when(gatewayResolver.resolve(any())).thenReturn(gateway);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            savedStatuses.add(payment.status());
            return payment;
        });
        when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.empty());
    }

    @Nested
    class FreshRequest {

        @Test
        void checkpointsAsRefundPending_beforeEverCallingTheGateway() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.refund(any(), any(), any())).thenReturn(GatewayOperation.succeeded("refunded"));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            service.refund(cmd);

            assertThat(savedStatuses).containsExactly(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);
            verify(idempotencyCheck).complete(eq(cmd.idempotencyKey()), any());
        }

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
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishAll(argThat(events -> !events.isEmpty()));
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
            Payment authorizedOnly = rehydrate(
                    id,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("0.00"),
                    usd("0.00"),
                    PaymentStatus.AUTHORIZED,
                    new IdempotencyKey("original-key-" + id));
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(authorizedOnly));
            RefundPaymentCommand cmd = command(id, Optional.of(usd("10.00")));

            assertThatThrownBy(() -> service.refund(cmd)).isInstanceOf(InvalidPaymentStateTransitionException.class);

            verifyNoInteractions(gateway);
            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
        }

        @Test
        void declinedByProvider_cancelsThePendingAttemptAndFreesTheKey() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.refund(any(), any(), any())).thenReturn(GatewayOperation.failed("rejected", "already_refunded"));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            assertThatThrownBy(() -> service.refund(cmd))
                    .isInstanceOf(PaymentDeclinedException.class)
                    .hasMessageContaining("already_refunded");

            // A clean decline is definitive, not ambiguous: the pending attempt is reverted and the
            // client's key is freed so a retry starts a genuinely new attempt.
            verify(idempotencyCheck).complete(eq(cmd.idempotencyKey()), any());
            verify(idempotencyCheck).abandon(cmd.idempotencyKey());
            assertThat(savedStatuses).containsExactly(PaymentStatus.REFUND_PENDING, PaymentStatus.CAPTURED);
        }

        @Test
        void gatewayUnreachable_leavesPaymentCheckpointedAsPendingWithoutThrowing() {
            PaymentId id = PaymentId.newId();
            Payment payment = capturedPayment(id);
            when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(payment));
            when(gateway.refund(any(), any(), any())).thenThrow(new IllegalStateException("mock gateway unreachable"));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            PaymentResult result = service.refund(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
            verify(paymentRepository).save(argThat(p -> p.status() == PaymentStatus.REFUND_PENDING));
            verify(idempotencyCheck).complete(eq(cmd.idempotencyKey()), any());
            verify(idempotencyCheck, never()).abandon(any());
        }
    }

    @Nested
    class ReplayedRequest {

        @Test
        void previouslyCompletedRefund_returnsItWithoutCallingTheGatewayAgain() {
            PaymentId id = PaymentId.newId();
            Payment refunded = rehydrate(
                    id,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("100.00"),
                    usd("100.00"),
                    PaymentStatus.REFUNDED,
                    new IdempotencyKey("original-key-" + id));
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
        void stillRefundPending_returnsItWithoutCallingTheGatewayAgain() {
            PaymentId id = PaymentId.newId();
            Payment pending = rehydrate(
                    id,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("100.00"),
                    usd("0.00"),
                    PaymentStatus.REFUND_PENDING,
                    new IdempotencyKey("original-key-" + id));
            when(idempotencyCheck.claim(any(), anyString())).thenReturn(Optional.of(id));
            when(paymentRepository.findById(id)).thenReturn(Optional.of(pending));
            RefundPaymentCommand cmd = command(id, Optional.empty());

            PaymentResult result = service.refund(cmd);

            assertThat(result.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
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
