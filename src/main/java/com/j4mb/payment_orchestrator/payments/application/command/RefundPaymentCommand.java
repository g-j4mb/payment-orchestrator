package com.j4mb.payment_orchestrator.payments.application.command;

import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * Request to refund a captured payment.
 *
 * @param amount the amount to refund; empty means refund everything still refundable
 */
public record RefundPaymentCommand(
        PaymentId paymentId, Optional<Money> amount, IdempotencyKey idempotencyKey, String reason) {

    public RefundPaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }
}