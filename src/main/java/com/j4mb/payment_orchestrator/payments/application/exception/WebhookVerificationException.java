package com.j4mb.payment_orchestrator.payments.application.exception;

import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;

/**
 * Raised when a webhook payload fails its provider's signature check.
 *
 * <p>Treat this as a rejected request, never as a transient error — an unverified payload must not
 * touch the aggregate.
 */
public class WebhookVerificationException extends RuntimeException {

    public WebhookVerificationException(ProviderType provider, String reason) {
        super("could not verify %s webhook signature: %s".formatted(provider, reason));
    }
}