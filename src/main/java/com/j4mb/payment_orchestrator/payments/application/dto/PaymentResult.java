package com.j4mb.payment_orchestrator.payments.application.dto;

import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of a payment returned by every use case.
 *
 * <p>Use cases return this rather than the aggregate itself, so callers cannot invoke lifecycle
 * methods outside a transaction or depend on the aggregate's internal shape.
 */
public record PaymentResult(
        UUID paymentId,
        ProviderType provider,
        String providerReference,
        Money authorizedAmount,
        Money capturedAmount,
        Money refundedAmount,
        PaymentStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResult from(Payment payment) {
        return new PaymentResult(
                payment.id().value(),
                payment.provider(),
                payment.providerReference().map(Object::toString).orElse(null),
                payment.authorizedAmount(),
                payment.capturedAmount(),
                payment.refundedAmount(),
                payment.status(),
                payment.failureReason().orElse(null),
                payment.createdAt(),
                payment.updatedAt());
    }
}