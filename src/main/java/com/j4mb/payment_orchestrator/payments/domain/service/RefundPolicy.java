package com.j4mb.payment_orchestrator.payments.domain.service;

import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;

/**
 * Domain service deciding whether a refund is allowed.
 *
 * <p>It lives outside {@link Payment} because refund eligibility is the kind of rule that grows
 * beyond the aggregate's own state — refund windows, provider-specific caps, or partial-refund
 * limits are natural extensions here.
 *
 * <p>Delegates the structural check to {@link Payment#ensureRefundable} rather than
 * re-implementing it, so this policy is never weaker than the aggregate's own invariant — only
 * ever stricter, once rules are layered on top.
 */
public class RefundPolicy {

    /**
     * Validates a refund against the payment's own invariants, plus any policy layered on top.
     *
     * @throws InvalidPaymentStateTransitionException if the payment is not in a refundable state
     * @throws InvalidRefundAmountException if the amount is zero or exceeds the refundable balance
     */
    public void validate(Payment payment, Money requestedAmount) {
        payment.ensureRefundable(requestedAmount);
        // TODO: extend with refund-window and provider-specific rules as they are defined.
    }
}