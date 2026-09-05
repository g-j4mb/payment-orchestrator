package com.j4mb.payment_orchestrator.payments.domain.model;

import com.j4mb.payment_orchestrator.common.AggregateRoot;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentAuthorized;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentCaptured;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentFailed;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentRefunded;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentVoided;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidCaptureAmountException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
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
    private final Instant createdAt;

    private ProviderReference providerReference;
    private PaymentStatus status;
    private Money capturedAmount;
    private Money refundedAmount;
    private String failureReason;
    private Instant updatedAt;

    private Payment(
            PaymentId id,
            ProviderType provider,
            Money authorizedAmount,
            IdempotencyKey idempotencyKey,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.authorizedAmount = Objects.requireNonNull(authorizedAmount, "amount must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotency key must not be null");
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
            ProviderType provider, Money amount, IdempotencyKey idempotencyKey, Instant now) {
        return new Payment(PaymentId.newId(), provider, amount, idempotencyKey, now);
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
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {
        Payment payment = new Payment(id, provider, authorizedAmount, idempotencyKey, createdAt);
        payment.providerReference = providerReference;
        payment.capturedAmount = Objects.requireNonNull(capturedAmount, "capturedAmount must not be null");
        payment.refundedAmount = Objects.requireNonNull(refundedAmount, "refundedAmount must not be null");
        payment.status = Objects.requireNonNull(status, "status must not be null");
        payment.failureReason = failureReason;
        payment.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        payment.pullDomainEvents();
        return payment;
    }

    /** Records a successful authorization at the provider. */
    public void markAuthorized(ProviderReference reference, Instant now) {
        if (status != PaymentStatus.CREATED) {
            throw new InvalidPaymentStateTransitionException(status, "authorize");
        }
        this.providerReference = Objects.requireNonNull(reference, "provider reference must not be null");
        this.status = PaymentStatus.AUTHORIZED;
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

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
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