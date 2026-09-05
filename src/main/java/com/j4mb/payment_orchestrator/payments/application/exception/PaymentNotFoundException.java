package com.j4mb.payment_orchestrator.payments.application.exception;

import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;

/** Raised when a requested payment does not exist. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(PaymentId paymentId) {
        super("payment not found: " + paymentId);
    }
}