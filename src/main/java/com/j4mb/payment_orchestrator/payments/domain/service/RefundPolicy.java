package com.j4mb.payment_orchestrator.payments.domain.service;

import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;

/**
 * Domain service deciding whether a refund is allowed.
 *
 * <p>It lives outside {@link Payment} because refund eligibility is the kind of rule that grows
 * beyond the aggregate's own state — refund windows, provider-specific caps, or partial-refund
 * limits are natural extensions here.
 */
public class RefundPolicy {

    /**
     * Validates a refund against what remains refundable on the payment.
     *
     * @throws InvalidRefundAmountException if the amount is zero or exceeds the refundable balance
     */
    public void validate(Payment payment, Money requestedAmount) {
        Money refundable = payment.refundableAmount();
        if (requestedAmount.isZero()) {
            throw new InvalidRefundAmountException(requestedAmount, refundable);
        }
        if (requestedAmount.isGreaterThan(refundable)) {
            throw new InvalidRefundAmountException(requestedAmount, refundable);
        }
        // TODO: extend with refund-window and provider-specific rules as they are defined.
    }
}