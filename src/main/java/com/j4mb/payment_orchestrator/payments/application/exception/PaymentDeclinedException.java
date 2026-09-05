package com.j4mb.payment_orchestrator.payments.application.exception;

import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;

/** Raised when a provider rejects an operation on a payment. */
public class PaymentDeclinedException extends RuntimeException {

    private final PaymentId paymentId;

    public PaymentDeclinedException(PaymentId paymentId, String reason) {
        super("payment %s was declined by the provider: %s".formatted(paymentId, reason));
        this.paymentId = paymentId;
    }

    public PaymentId paymentId() {
        return paymentId;
    }
}