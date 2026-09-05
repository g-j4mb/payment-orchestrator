package com.j4mb.payment_orchestrator.payments.domain.vo;

import java.util.Objects;

/**
 * A caller-supplied key that makes a mutating request safely repeatable.
 *
 * <p>Replaying a request with the same key must return the original outcome instead of creating a
 * second payment.
 */
public record IdempotencyKey(String value) {

    private static final int MAX_LENGTH = 255;

    public IdempotencyKey {
        Objects.requireNonNull(value, "idempotency key must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotency key must be at most %d characters".formatted(MAX_LENGTH));
        }
    }

    @Override
    public String toString() {
        return value;
    }
}