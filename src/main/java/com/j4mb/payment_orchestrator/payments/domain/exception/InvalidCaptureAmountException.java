package com.j4mb.payment_orchestrator.payments.domain.exception;

import com.j4mb.payment_orchestrator.payments.domain.vo.Money;

/** Raised when a capture would exceed what remains authorized on a payment. */
public class InvalidCaptureAmountException extends RuntimeException {

    private final Money requested;
    private final Money capturable;

    public InvalidCaptureAmountException(Money requested, Money capturable) {
        super("capture of %s exceeds the capturable amount of %s".formatted(requested, capturable));
        this.requested = requested;
        this.capturable = capturable;
    }

    public Money requested() {
        return requested;
    }

    public Money capturable() {
        return capturable;
    }
}
