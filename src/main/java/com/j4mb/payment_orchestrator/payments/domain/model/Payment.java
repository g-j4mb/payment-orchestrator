package com.j4mb.payment_orchestrator.payments.domain.model;

import com.j4mb.payment_orchestrator.common.AggregateRoot;
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
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for the payments context.
 *
 * <p>Owns the payment lifecycle and its invariants: you cannot capture more than was authorized,
 * refund more than was captured, or void a payment that has already been captured. Every accepted
 * transition raises a domain event, which is what feeds the audit log.
 *
 * <p>State is changed only through the methods below — never by setters — so no caller can put a
 * payment into a state the domain considers impossible.
 */
public class Payment extends AggregateRoot<PaymentId> {

    private final PaymentId id;
    private final ProviderType provider;
    private final Money authorizedAmount;
    private final IdempotencyKey idempotencyKey;
    private final String paymentMethodToken;
    private final CaptureMode captureMode;
    private final Instant createdAt;

    private ProviderReference providerReference;
    private PaymentStatus status;
    private Money capturedAmount;
    private Money refundedAmount;
    private String failureReason;
    private int reconciliationAttempts;
    private Instant pendingSince;
    private Money pendingRefundAmount;
    private String pendingRefundReason;
    private String refundAttemptIdempotencyKey;
    private Instant updatedAt;

    private Payment(
            PaymentId id,
            ProviderType provider,
            Money authorizedAmount,
            IdempotencyKey idempotencyKey,
            String paymentMethodToken,
            CaptureMode captureMode,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.authorizedAmount = Objects.requireNonNull(authorizedAmount, "amount must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotency key must not be null");
        this.paymentMethodToken =
                Objects.requireNonNull(paymentMethodToken, "paymentMethodToken must not be null");
        this.captureMode = Objects.requireNonNull(captureMode, "captureMode must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (authorizedAmount.isZero()) {
            throw new IllegalArgumentException("payment amount must be greater than zero");
        }
        this.status = PaymentStatus.CREATED;
        this.capturedAmount = Money.zero(authorizedAmount.currency());
        this.refundedAmount = Money.zero(authorizedAmount.currency());
        this.updatedAt = createdAt;
    }

    /** Creates a payment that has not yet been sent to a provider. */
    public static Payment initiate(
            ProviderType provider,
            Money amount,
            IdempotencyKey idempotencyKey,
            String paymentMethodToken,
            CaptureMode captureMode,
            Instant now) {
        return new Payment(PaymentId.newId(), provider, amount, idempotencyKey, paymentMethodToken, captureMode, now);
    }

    /**
     * Rebuilds an aggregate from persisted state.
     *
     * <p>Used only by the persistence adapter; it bypasses the lifecycle deliberately and raises no
     * events.
     */
    public static Payment rehydrate(
            PaymentId id,
            ProviderType provider,
            ProviderReference providerReference,
            Money authorizedAmount,
            Money capturedAmount,
            Money refundedAmount,
            PaymentStatus status,
            IdempotencyKey idempotencyKey,
            String paymentMethodToken,
            CaptureMode captureMode,
            String failureReason,
            int reconciliationAttempts,
            Instant pendingSince,
            Money pendingRefundAmount,
            String pendingRefundReason,
            String refundAttemptIdempotencyKey,
            Instant createdAt,
            Instant updatedAt) {
        Payment payment =
                new Payment(id, provider, authorizedAmount, idempotencyKey, paymentMethodToken, captureMode, createdAt);
        payment.providerReference = providerReference;
        payment.capturedAmount = Objects.requireNonNull(capturedAmount, "capturedAmount must not be null");
        payment.refundedAmount = Objects.requireNonNull(refundedAmount, "refundedAmount must not be null");
        payment.status = Objects.requireNonNull(status, "status must not be null");
        payment.failureReason = failureReason;
        payment.reconciliationAttempts = reconciliationAttempts;
        payment.pendingSince = pendingSince;
        payment.pendingRefundAmount = pendingRefundAmount;
        payment.pendingRefundReason = pendingRefundReason;
        payment.refundAttemptIdempotencyKey = refundAttemptIdempotencyKey;
        payment.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        payment.pullDomainEvents();
        return payment;
    }

    /**
     * Records that an authorize call was sent to the provider and its outcome is not yet known.
     *
     * <p>Committed durably before the provider is ever called — see {@code AuthorizePaymentService}
     * — so a crash mid-call still leaves a record reconciliation can act on, rather than losing the
     * attempt entirely.
     */
    public void markAuthorizationPending(Instant now) {
        if (status != PaymentStatus.CREATED) {
            throw new InvalidPaymentStateTransitionException(status, "mark authorization pending");
        }
        this.status = PaymentStatus.AUTHORIZATION_PENDING;
        this.pendingSince = now;
        this.updatedAt = now;
        registerEvent(new PaymentAuthorizationPending(id, provider, now));
    }

    /**
     * Records another reconciliation pass that still could not confirm the outcome — valid while
     * either an authorization or a refund attempt is pending, since both share this same counter.
     */
    public void recordReconciliationAttempt(Instant now) {
        if (status != PaymentStatus.AUTHORIZATION_PENDING && status != PaymentStatus.REFUND_PENDING) {
            throw new InvalidPaymentStateTransitionException(status, "record reconciliation attempt");
        }
        this.reconciliationAttempts++;
        this.updatedAt = now;
    }

    /**
     * Records a successful authorization at the provider.
     *
     * <p>Valid from {@code AUTHORIZATION_PENDING} as well as {@code CREATED}: reconciliation resolves
     * a pending authorization through this same method, not a separate one.
     */
    public void markAuthorized(ProviderReference reference, Instant now) {
        if (status != PaymentStatus.CREATED && status != PaymentStatus.AUTHORIZATION_PENDING) {
            throw new InvalidPaymentStateTransitionException(status, "authorize");
        }
        this.providerReference = Objects.requireNonNull(reference, "provider reference must not be null");
        this.status = PaymentStatus.AUTHORIZED;
        this.pendingSince = null;
        this.updatedAt = now;
        registerEvent(new PaymentAuthorized(id, provider, reference, authorizedAmount, now));
    }

    /**
     * Checks that {@code amount} could be captured, without capturing it.
     *
     * <p>Exists so a caller can reject an impossible capture <em>before</em> asking a provider to
     * move money. Discovering it afterwards would mean the funds were taken while this aggregate
     * refused to record them.
     *
     * @throws InvalidPaymentStateTransitionException if the payment is not in a capturable state
     * @throws InvalidCaptureAmountException if the amount is zero or exceeds the capturable balance
     */
    public void ensureCapturable(Money amount) {
        if (!status.isCapturable()) {
            throw new InvalidPaymentStateTransitionException(status, "capture");
        }
        Money capturable = capturableAmount();
        if (amount.isZero() || amount.isGreaterThan(capturable)) {
            throw new InvalidCaptureAmountException(amount, capturable);
        }
    }

    /**
     * Records a capture of {@code amount}, moving to {@code PARTIALLY_CAPTURED} while an authorized
     * balance remains and {@code CAPTURED} once it is exhausted.
     */
    public void capture(Money amount, Instant now) {
        // Re-checked here rather than trusted from the caller: the aggregate is the last line of
        // defense for its own invariants, whoever else looked first.
        ensureCapturable(amount);
        this.capturedAmount = capturedAmount.plus(amount);
        this.status = capturedAmount.compareTo(authorizedAmount) == 0
                ? PaymentStatus.CAPTURED
                : PaymentStatus.PARTIALLY_CAPTURED;
        this.updatedAt = now;
        registerEvent(new PaymentCaptured(id, provider, amount, capturedAmount, now));
    }

    /**
     * Checks that {@code amount} could be refunded, without refunding it.
     *
     * <p>Same reason as {@link #ensureCapturable}: a refund the aggregate would refuse must be
     * rejected before a provider returns the money.
     *
     * @throws InvalidPaymentStateTransitionException if the payment is not in a refundable state
     * @throws InvalidRefundAmountException if the amount is zero or exceeds the refundable balance
     */
    public void ensureRefundable(Money amount) {
        if (!status.isRefundable()) {
            throw new InvalidPaymentStateTransitionException(status, "refund");
        }
        Money refundable = refundableAmount();
        if (amount.isZero() || amount.isGreaterThan(refundable)) {
            throw new InvalidRefundAmountException(amount, refundable);
        }
    }

    /**
     * Records a refund of {@code amount} against captured funds.
     *
     * <p>Eligibility beyond the aggregate's own balance is decided by {@code RefundPolicy}; the check
     * here is the aggregate's own last-line invariant.
     */
    public void refund(Money amount, Instant now) {
        ensureRefundable(amount);
        this.refundedAmount = refundedAmount.plus(amount);
        this.status = refundedAmount.compareTo(capturedAmount) == 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        this.updatedAt = now;
        registerEvent(new PaymentRefunded(id, provider, amount, refundedAmount, now));
    }

    /**
     * Records that a refund call is about to be sent to the provider, before it is actually sent.
     *
     * <p>Committed durably before the provider is ever called — see {@code RefundPaymentService} —
     * so a crash mid-call still leaves a record reconciliation can act on. Also blocks a second
     * refund attempt from starting while this one is unresolved: {@code REFUND_PENDING} is not in
     * {@link PaymentStatus#isRefundable()}'s set, so {@link #ensureRefundable} rejects one.
     *
     * @throws InvalidPaymentStateTransitionException if the payment is not in a refundable state
     * @throws InvalidRefundAmountException if the amount is zero or exceeds the refundable balance
     */
    public void beginRefundAttempt(Money amount, String reason, String idempotencyKey, Instant now) {
        ensureRefundable(amount);
        this.pendingRefundAmount = amount;
        this.pendingRefundReason = reason;
        this.refundAttemptIdempotencyKey =
                Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.status = PaymentStatus.REFUND_PENDING;
        this.pendingSince = now;
        this.updatedAt = now;
        registerEvent(new PaymentRefundPending(id, provider, amount, now));
    }

    /** Applies the in-flight refund attempt now that the provider has confirmed it succeeded. */
    public void resolveRefundPending(Instant now) {
        if (status != PaymentStatus.REFUND_PENDING) {
            throw new InvalidPaymentStateTransitionException(status, "resolve refund");
        }
        Money amount = pendingRefundAmount;
        this.refundedAmount = refundedAmount.plus(amount);
        this.status = refundedAmount.compareTo(capturedAmount) == 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        clearPendingRefundAttempt();
        this.updatedAt = now;
        registerEvent(new PaymentRefunded(id, provider, amount, refundedAmount, now));
    }

    /**
     * Reverts an in-flight refund attempt the provider confirmed never actually happened, without
     * touching {@code refundedAmount} — nothing was refunded, so there is nothing to undo.
     *
     * <p>Reconstructs whichever of the three refundable statuses this payment was in before the
     * attempt started, from the balances alone: {@code refundedAmount} did not change while pending,
     * so a nonzero value means it was {@code PARTIALLY_REFUNDED}; otherwise it was {@code CAPTURED}
     * or {@code PARTIALLY_CAPTURED} depending on whether the full authorized amount was captured.
     */
    public void cancelRefundPending(Instant now) {
        if (status != PaymentStatus.REFUND_PENDING) {
            throw new InvalidPaymentStateTransitionException(status, "cancel pending refund");
        }
        this.status = !refundedAmount.isZero()
                ? PaymentStatus.PARTIALLY_REFUNDED
                : capturedAmount.compareTo(authorizedAmount) == 0
                        ? PaymentStatus.CAPTURED
                        : PaymentStatus.PARTIALLY_CAPTURED;
        clearPendingRefundAttempt();
        this.updatedAt = now;
    }

    private void clearPendingRefundAttempt() {
        this.pendingRefundAmount = null;
        this.pendingRefundReason = null;
        this.refundAttemptIdempotencyKey = null;
        this.pendingSince = null;
    }

    /**
     * Checks that the authorization could be released, without releasing it.
     *
     * @throws InvalidPaymentStateTransitionException if anything has already been captured
     */
    public void ensureVoidable() {
        if (!status.isVoidable()) {
            throw new InvalidPaymentStateTransitionException(status, "void");
        }
    }

    /** Releases the authorization. Only possible while nothing has been captured. */
    public void markVoided(Instant now) {
        ensureVoidable();
        this.status = PaymentStatus.VOIDED;
        this.updatedAt = now;
        registerEvent(new PaymentVoided(id, provider, now));
    }

    /** Records a provider rejection. Terminal states are left untouched. */
    public void markFailed(String reason, Instant now) {
        if (status.isTerminal()) {
            throw new InvalidPaymentStateTransitionException(status, "fail");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.pendingSince = null;
        this.updatedAt = now;
        registerEvent(new PaymentFailed(id, provider, reason, now));
    }

    /** Authorized funds not yet captured. */
    public Money capturableAmount() {
        return authorizedAmount.minus(capturedAmount);
    }

    /** Captured funds not yet refunded. */
    public Money refundableAmount() {
        return capturedAmount.minus(refundedAmount);
    }

    @Override
    public PaymentId id() {
        return id;
    }

    public ProviderType provider() {
        return provider;
    }

    public Optional<ProviderReference> providerReference() {
        return Optional.ofNullable(providerReference);
    }

    public Money authorizedAmount() {
        return authorizedAmount;
    }

    public Money capturedAmount() {
        return capturedAmount;
    }

    public Money refundedAmount() {
        return refundedAmount;
    }

    public PaymentStatus status() {
        return status;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public String paymentMethodToken() {
        return paymentMethodToken;
    }

    public CaptureMode captureMode() {
        return captureMode;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /** How many reconciliation passes have run against this payment without resolving it. */
    public int reconciliationAttempts() {
        return reconciliationAttempts;
    }

    /**
     * When the current pending attempt (authorization or refund) began, for measuring how long it
     * has been unresolved — distinct from {@link #updatedAt()}, which a reconciliation pass bumps on
     * every attempt and so cannot answer that question. Empty outside {@code AUTHORIZATION_PENDING}
     * and {@code REFUND_PENDING}.
     */
    public Optional<Instant> pendingSince() {
        return Optional.ofNullable(pendingSince);
    }

    /** The amount of the in-flight refund attempt, if {@link #status()} is {@code REFUND_PENDING}. */
    public Optional<Money> pendingRefundAmount() {
        return Optional.ofNullable(pendingRefundAmount);
    }

    public Optional<String> pendingRefundReason() {
        return Optional.ofNullable(pendingRefundReason);
    }

    /** The idempotency key the in-flight refund attempt was — and must again be — sent under. */
    public Optional<String> refundAttemptIdempotencyKey() {
        return Optional.ofNullable(refundAttemptIdempotencyKey);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Payment payment && id.equals(payment.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}