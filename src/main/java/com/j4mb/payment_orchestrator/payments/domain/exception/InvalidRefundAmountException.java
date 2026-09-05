package com.j4mb.payment_orchestrator.payments.domain.exception;

import com.j4mb.payment_orchestrator.payments.domain.vo.Money;

/** Raised when a refund would exceed what remains refundable on a payment. */
public class InvalidRefundAmountException extends RuntimeException {

    private final Money requested;
    private final Money refundable;

    public InvalidRefundAmountException(Money requested, Money refundable) {
        super("refund of %s exceeds the refundable amount of %s".formatted(requested, refundable));
        this.requested = requested;
        this.refundable = refundable;
    }

    public Money requested() {
        return requested;
    }

    public Money refundable() {
        return refundable;
    }
}