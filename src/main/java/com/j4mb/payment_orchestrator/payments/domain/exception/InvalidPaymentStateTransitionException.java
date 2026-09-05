package com.j4mb.payment_orchestrator.payments.domain.exception;

import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;

/** Raised when an operation is attempted from a state that does not allow it. */
public class InvalidPaymentStateTransitionException extends RuntimeException {

    private final PaymentStatus currentStatus;
    private final String operation;

    public InvalidPaymentStateTransitionException(PaymentStatus currentStatus, String operation) {
        super("cannot %s a payment in state %s".formatted(operation, currentStatus));
        this.currentStatus = currentStatus;
        this.operation = operation;
    }

    public PaymentStatus currentStatus() {
        return currentStatus;
    }

    public String operation() {
        return operation;
    }
}