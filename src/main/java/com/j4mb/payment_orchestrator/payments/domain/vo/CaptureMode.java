package com.j4mb.payment_orchestrator.payments.domain.vo;

/**
 * Whether a provider should capture funds immediately after authorizing, or hold the authorization
 * for a later, separate capture.
 *
 * <p>Lives in the domain, not on {@code AuthorizePaymentCommand}, because {@link
 * com.j4mb.payment_orchestrator.payments.domain.model.Payment} must remember it for the life of the
 * authorization attempt: reconciling an ambiguous authorize call means resending the exact same
 * request, and Stripe rejects reusing an idempotency key with different parameters.
 */
public enum CaptureMode {
    AUTOMATIC,
    MANUAL
}
