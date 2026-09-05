package com.j4mb.payment_orchestrator.payments.application.command;

import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import java.util.Objects;

/** Request to release an authorization before capture. */
public record VoidPaymentCommand(PaymentId paymentId, IdempotencyKey idempotencyKey, String reason) {

    public VoidPaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }
}