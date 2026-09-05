package com.j4mb.payment_orchestrator.payments.domain.vo;

import java.util.Locale;

/**
 * The external payment providers this orchestrator can route to.
 *
 * <p>This is the strategy selector: each value has exactly one gateway adapter behind the outbound
 * gateway port. Adding a provider means adding a constant and an adapter — no change to the domain
 * or application layers.
 */
public enum ProviderType {
    STRIPE,
    ADYEN,
    CHECKOUT;

    /** Parses a provider name case-insensitively, as it arrives on API and webhook paths. */
    public static ProviderType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown provider: " + value, ex);
        }
    }
}