package com.j4mb.payment_orchestrator.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j4mb.payment_orchestrator.payments.domain.event.PaymentAuthorizationPending;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentAuthorized;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentCaptured;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentFailed;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentRefundPending;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentRefunded;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentVoided;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidCaptureAmountException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final String TOKEN = "tok_visa";

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static IdempotencyKey newKey() {
        return new IdempotencyKey("key-" + System.nanoTime());
    }

    private static Payment newPayment(Money amount) {
        return Payment.initiate(ProviderType.STRIPE, amount, newKey(), TOKEN, CaptureMode.MANUAL, NOW);
    }

    /** Fills in the reconciliation-related fields with "nothing in flight" defaults. */
    private static Payment rehydrate(
            PaymentId id,
            ProviderType provider,
            ProviderReference reference,
            Money authorizedAmount,
            Money capturedAmount,
            Money refundedAmount,
            PaymentStatus status,
            IdempotencyKey key,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {
        return Payment.rehydrate(
                id,
                provider,
                reference,
                authorizedAmount,
                capturedAmount,
                refundedAmount,
                status,
                key,
                TOKEN,
                CaptureMode.MANUAL,
                failureReason,
                0,
                null,
                null,
                null,
                null,
                createdAt,
                updatedAt);
    }

    @Nested
    class Initiate {

        @Test
        void rejectsZeroAmount() {
            assertThatThrownBy(() -> newPayment(usd("0.00"))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void startsCreatedWithNoCapturedOrRefundedFunds() {
            Payment payment = newPayment(usd("100.00"));

            assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
            assertThat(payment.capturedAmount()).isEqualTo(usd("0.00"));
            assertThat(payment.refundedAmount()).isEqualTo(usd("0.00"));
            assertThat(payment.providerReference()).isEmpty();
        }

        @Test
        void exposesTheFieldsItWasBuiltFrom() {
            IdempotencyKey key = newKey();
            Payment payment = Payment.initiate(ProviderType.ADYEN, usd("100.00"), key, TOKEN, CaptureMode.AUTOMATIC, NOW);

            assertThat(payment.id()).isNotNull();
            assertThat(payment.provider()).isEqualTo(ProviderType.ADYEN);
            assertThat(payment.authorizedAmount()).isEqualTo(usd("100.00"));
            assertThat(payment.idempotencyKey()).isEqualTo(key);
            assertThat(payment.paymentMethodToken()).isEqualTo(TOKEN);
            assertThat(payment.captureMode()).isEqualTo(CaptureMode.AUTOMATIC);
            assertThat(payment.createdAt()).isEqualTo(NOW);
            assertThat(payment.updatedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    class MarkAuthorizationPending {

        @Test
        void fromCreated_setsPendingStatus_recordsPendingSince_andRaisesEvent() {
            Payment payment = newPayment(usd("100.00"));

            payment.markAuthorizationPending(NOW);

            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZATION_PENDING);
            assertThat(payment.pendingSince()).contains(NOW);
            assertThat(payment.pullDomainEvents()).singleElement().isInstanceOf(PaymentAuthorizationPending.class);
        }

        @Test
        void whenNotCreated_throws() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);

            assertThatThrownBy(() -> payment.markAuthorizationPending(LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class RecordReconciliationAttempt {

        @Test
        void whileAuthorizationPending_incrementsCounter() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorizationPending(NOW);

            payment.recordReconciliationAttempt(LATER);

            assertThat(payment.reconciliationAttempts()).isEqualTo(1);
            assertThat(payment.updatedAt()).isEqualTo(LATER);
        }

        @Test
        void whileRefundPending_incrementsSharedCounter() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);
            payment.capture(usd("100.00"), NOW);
            payment.beginRefundAttempt(usd("40.00"), "requested_by_customer", "attempt-1", NOW);

            payment.recordReconciliationAttempt(LATER);

            assertThat(payment.reconciliationAttempts()).isEqualTo(1);
        }

        @Test
        void whenNeitherPending_throws() {
            Payment payment = newPayment(usd("100.00"));

            assertThatThrownBy(() -> payment.recordReconciliationAttempt(LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class MarkAuthorized {

        @Test
        void fromCreated_setsAuthorizedStatusAndReference_andRaisesEvent() {
            Payment payment = newPayment(usd("100.00"));
            ProviderReference reference = new ProviderReference("pi_123");

            payment.markAuthorized(reference, LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.providerReference()).contains(reference);
            assertThat(payment.updatedAt()).isEqualTo(LATER);
            assertThat(payment.pullDomainEvents())
                    .singleElement()
                    .isInstanceOfSatisfying(
                            PaymentAuthorized.class,
                            event -> assertThat(event.providerReference()).isEqualTo(reference));
        }

        @Test
        void fromAuthorizationPending_alsoSucceeds_andClearsPendingSince() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorizationPending(NOW);
            ProviderReference reference = new ProviderReference("pi_123");

            payment.markAuthorized(reference, LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.pendingSince()).isEmpty();
        }

        @Test
        void whenAlreadyAuthorized_throws() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);

            assertThatThrownBy(() -> payment.markAuthorized(new ProviderReference("pi_456"), LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class Capture {

        private Payment payment;

        @BeforeEach
        void authorizedPayment() {
            payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);
            payment.pullDomainEvents();
        }

        @Test
        void partialAmount_movesToPartiallyCaptured() {
            payment.capture(usd("40.00"), LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
            assertThat(payment.capturedAmount()).isEqualTo(usd("40.00"));
            assertThat(payment.capturableAmount()).isEqualTo(usd("60.00"));
            assertThat(payment.pullDomainEvents()).hasOnlyElementsOfType(PaymentCaptured.class);
        }

        @Test
        void fullRemainingBalance_movesToCaptured() {
            payment.capture(usd("100.00"), LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.capturableAmount()).isEqualTo(usd("0.00"));
        }

        @Test
        void moreThanCapturable_throwsAndLeavesStateUnchanged() {
            assertThatThrownBy(() -> payment.capture(usd("150.00"), LATER))
                    .isInstanceOf(InvalidCaptureAmountException.class);
            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.capturedAmount()).isEqualTo(usd("0.00"));
        }

        @Test
        void zeroAmount_throws() {
            assertThatThrownBy(() -> payment.capture(usd("0.00"), LATER))
                    .isInstanceOf(InvalidCaptureAmountException.class);
        }

        @Test
        void fromNonCapturableStatus_throws() {
            Payment created = newPayment(usd("100.00"));

            assertThatThrownBy(() -> created.capture(usd("10.00"), LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class Refund {

        private Payment payment;

        @BeforeEach
        void capturedPayment() {
            payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);
            payment.capture(usd("100.00"), NOW);
            payment.pullDomainEvents();
        }

        @Test
        void partialAmount_movesToPartiallyRefunded() {
            payment.refund(usd("30.00"), LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(payment.refundedAmount()).isEqualTo(usd("30.00"));
            assertThat(payment.refundableAmount()).isEqualTo(usd("70.00"));
            assertThat(payment.pullDomainEvents()).hasOnlyElementsOfType(PaymentRefunded.class);
        }

        @Test
        void fullCapturedAmount_movesToRefunded() {
            payment.refund(usd("100.00"), LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.refundableAmount()).isEqualTo(usd("0.00"));
        }

        @Test
        void moreThanRefundable_throwsAndLeavesStateUnchanged() {
            assertThatThrownBy(() -> payment.refund(usd("150.00"), LATER))
                    .isInstanceOf(InvalidRefundAmountException.class);
            assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.refundedAmount()).isEqualTo(usd("0.00"));
        }

        @Test
        void fromNonRefundableStatus_throws() {
            Payment authorizedOnly = newPayment(usd("100.00"));
            authorizedOnly.markAuthorized(new ProviderReference("pi_123"), NOW);

            assertThatThrownBy(() -> authorizedOnly.refund(usd("10.00"), LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class RefundPendingLifecycle {

        private Payment payment;

        @BeforeEach
        void capturedPayment() {
            payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);
            payment.capture(usd("100.00"), NOW);
            payment.pullDomainEvents();
        }

        @Test
        void beginRefundAttempt_movesToPendingAndRaisesEvent() {
            payment.beginRefundAttempt(usd("40.00"), "requested_by_customer", "attempt-1", NOW);

            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
            assertThat(payment.pendingRefundAmount()).contains(usd("40.00"));
            assertThat(payment.pendingRefundReason()).contains("requested_by_customer");
            assertThat(payment.refundAttemptIdempotencyKey()).contains("attempt-1");
            assertThat(payment.pendingSince()).contains(NOW);
            assertThat(payment.pullDomainEvents()).singleElement().isInstanceOf(PaymentRefundPending.class);
        }

        @Test
        void blocksASecondRefundAttemptWhilePending() {
            payment.beginRefundAttempt(usd("40.00"), "reason", "attempt-1", NOW);

            assertThatThrownBy(() -> payment.beginRefundAttempt(usd("10.00"), "reason", "attempt-2", LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }

        @Test
        void resolveRefundPending_appliesTheAmountAndClearsPendingState() {
            payment.beginRefundAttempt(usd("40.00"), "reason", "attempt-1", NOW);
            payment.pullDomainEvents();

            payment.resolveRefundPending(LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(payment.refundedAmount()).isEqualTo(usd("40.00"));
            assertThat(payment.pendingRefundAmount()).isEmpty();
            assertThat(payment.pendingSince()).isEmpty();
            assertThat(payment.pullDomainEvents()).singleElement().isInstanceOf(PaymentRefunded.class);
        }

        @Test
        void resolveRefundPending_fullAmount_movesToRefunded() {
            payment.beginRefundAttempt(usd("100.00"), "reason", "attempt-1", NOW);

            payment.resolveRefundPending(LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        void cancelRefundPending_fromFullyCaptured_revertsToCaptured() {
            payment.beginRefundAttempt(usd("40.00"), "reason", "attempt-1", NOW);

            payment.cancelRefundPending(LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.refundedAmount()).isEqualTo(usd("0.00"));
            assertThat(payment.pendingRefundAmount()).isEmpty();
            assertThat(payment.pendingSince()).isEmpty();
        }

        @Test
        void cancelRefundPending_afterAPriorPartialRefund_revertsToPartiallyRefunded() {
            payment.refund(usd("20.00"), NOW);
            payment.beginRefundAttempt(usd("30.00"), "reason", "attempt-1", NOW);

            payment.cancelRefundPending(LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(payment.refundedAmount()).isEqualTo(usd("20.00"));
        }

        @Test
        void cancelRefundPending_fromPartiallyCaptured_revertsToPartiallyCaptured() {
            Payment partiallyCaptured = newPayment(usd("100.00"));
            partiallyCaptured.markAuthorized(new ProviderReference("pi_456"), NOW);
            partiallyCaptured.capture(usd("60.00"), NOW);
            partiallyCaptured.beginRefundAttempt(usd("20.00"), "reason", "attempt-1", NOW);

            partiallyCaptured.cancelRefundPending(LATER);

            assertThat(partiallyCaptured.status()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
        }

        @Test
        void cancelRefundPending_whenNotPending_throws() {
            assertThatThrownBy(() -> payment.cancelRefundPending(LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }

        @Test
        void resolveRefundPending_whenNotPending_throws() {
            assertThatThrownBy(() -> payment.resolveRefundPending(LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class MarkVoided {

        @Test
        void fromAuthorized_releasesFundsAndRaisesEvent() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);
            payment.pullDomainEvents();

            payment.markVoided(LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.VOIDED);
            assertThat(payment.pullDomainEvents()).hasOnlyElementsOfType(PaymentVoided.class);
        }

        @Test
        void beforeAuthorization_throws() {
            Payment payment = newPayment(usd("100.00"));

            assertThatThrownBy(() -> payment.markVoided(LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }

        @Test
        void afterCapture_throws() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);
            payment.capture(usd("100.00"), NOW);

            assertThatThrownBy(() -> payment.markVoided(LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class Fail {

        @Test
        void fromNonTerminalStatus_setsFailedAndReason() {
            Payment payment = newPayment(usd("100.00"));

            payment.markFailed("card_declined", LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.failureReason()).contains("card_declined");
            assertThat(payment.pullDomainEvents()).hasOnlyElementsOfType(PaymentFailed.class);
        }

        @Test
        void fromAuthorizationPending_alsoSucceeds() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorizationPending(NOW);

            payment.markFailed("card_declined", LATER);

            assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        void fromTerminalStatus_throws() {
            Payment payment = newPayment(usd("100.00"));
            payment.markAuthorized(new ProviderReference("pi_123"), NOW);
            payment.markVoided(LATER);

            assertThatThrownBy(() -> payment.markFailed("too_late", LATER))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    class Rehydrate {

        @Test
        void reconstructsFieldsExactlyAndRaisesNoEvents() {
            PaymentId id = PaymentId.newId();
            IdempotencyKey key = newKey();
            ProviderReference reference = new ProviderReference("pi_123");

            Payment payment = rehydrate(
                    id,
                    ProviderType.CHECKOUT,
                    reference,
                    usd("100.00"),
                    usd("40.00"),
                    usd("10.00"),
                    PaymentStatus.PARTIALLY_CAPTURED,
                    key,
                    null,
                    NOW,
                    LATER);

            assertThat(payment.id()).isEqualTo(id);
            assertThat(payment.provider()).isEqualTo(ProviderType.CHECKOUT);
            assertThat(payment.providerReference()).contains(reference);
            assertThat(payment.authorizedAmount()).isEqualTo(usd("100.00"));
            assertThat(payment.capturedAmount()).isEqualTo(usd("40.00"));
            assertThat(payment.refundedAmount()).isEqualTo(usd("10.00"));
            assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
            assertThat(payment.idempotencyKey()).isEqualTo(key);
            assertThat(payment.failureReason()).isEmpty();
            assertThat(payment.createdAt()).isEqualTo(NOW);
            assertThat(payment.updatedAt()).isEqualTo(LATER);
            assertThat(payment.pullDomainEvents()).isEmpty();
        }

        @Test
        void reconstructsReconciliationAndPendingRefundFields() {
            PaymentId id = PaymentId.newId();
            IdempotencyKey key = newKey();

            Payment payment = Payment.rehydrate(
                    id,
                    ProviderType.STRIPE,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("100.00"),
                    usd("0.00"),
                    PaymentStatus.REFUND_PENDING,
                    key,
                    TOKEN,
                    CaptureMode.AUTOMATIC,
                    null,
                    2,
                    NOW,
                    usd("40.00"),
                    "requested_by_customer",
                    "attempt-1",
                    NOW,
                    LATER);

            assertThat(payment.reconciliationAttempts()).isEqualTo(2);
            assertThat(payment.pendingSince()).contains(NOW);
            assertThat(payment.pendingRefundAmount()).contains(usd("40.00"));
            assertThat(payment.pendingRefundReason()).contains("requested_by_customer");
            assertThat(payment.refundAttemptIdempotencyKey()).contains("attempt-1");
            assertThat(payment.captureMode()).isEqualTo(CaptureMode.AUTOMATIC);
        }
    }

    @Nested
    class EqualsAndHashCode {

        @Test
        void paymentsWithTheSameId_areEqual_evenWithDifferentState() {
            PaymentId id = PaymentId.newId();
            IdempotencyKey key = newKey();
            Payment created = rehydrate(
                    id,
                    ProviderType.STRIPE,
                    null,
                    usd("100.00"),
                    usd("0.00"),
                    usd("0.00"),
                    PaymentStatus.CREATED,
                    key,
                    null,
                    NOW,
                    NOW);
            Payment captured = rehydrate(
                    id,
                    ProviderType.STRIPE,
                    new ProviderReference("pi_123"),
                    usd("100.00"),
                    usd("100.00"),
                    usd("0.00"),
                    PaymentStatus.CAPTURED,
                    key,
                    null,
                    NOW,
                    LATER);

            assertThat(created).isEqualTo(captured).hasSameHashCodeAs(captured);
        }

        @Test
        void paymentsWithDifferentIds_areNeverEqual() {
            Payment first = newPayment(usd("100.00"));
            Payment second = newPayment(usd("100.00"));

            assertThat(first).isNotEqualTo(second);
        }
    }
}
