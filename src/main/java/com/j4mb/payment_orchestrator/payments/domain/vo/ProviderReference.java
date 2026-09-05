package com.j4mb.payment_orchestrator.payments.domain.vo;

import java.util.Objects;

/**
 * The identifier a provider assigns to a payment on its own side (Stripe's {@code pi_...}, Adyen's
 * PSP reference, and so on).
 *
 * <p>This is how a webhook is correlated back to the local aggregate.
 */
public record ProviderReference(String value) {

    public ProviderReference {
        Objects.requireNonNull(value, "provider reference must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("provider reference must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}