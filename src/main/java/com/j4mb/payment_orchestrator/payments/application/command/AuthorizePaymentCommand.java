package com.j4mb.payment_orchestrator.payments.application.command;

import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.Objects;

/**
 * Request to authorize a payment.
 *
 * @param captureMode {@code AUTOMATIC} makes this a purchase (sale): authorize and capture in one
 *     step. {@code MANUAL} authorizes only, leaving capture to a later call.
 */
public record AuthorizePaymentCommand(
        ProviderType provider,
        Money amount,
        IdempotencyKey idempotencyKey,
        CaptureMode captureMode,
        String paymentMethodToken,
        String description) {

    public AuthorizePaymentCommand {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(captureMode, "captureMode must not be null");
    }

    /** Whether the provider should capture immediately after authorizing. */
    public enum CaptureMode {
        AUTOMATIC,
        MANUAL
    }
}