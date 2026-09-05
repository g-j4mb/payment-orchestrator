package com.j4mb.payment_orchestrator.payments.application.command;

import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * Request to capture an authorized payment.
 *
 * @param amount the amount to capture; empty means capture everything still authorized
 */
public record CapturePaymentCommand(
        PaymentId paymentId, Optional<Money> amount, IdempotencyKey idempotencyKey) {

    public CapturePaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }
}