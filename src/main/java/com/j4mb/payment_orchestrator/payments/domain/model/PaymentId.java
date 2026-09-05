package com.j4mb.payment_orchestrator.payments.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identity of a {@link Payment} aggregate. */
public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "payment id must not be null");
    }

    /** Generates an identity for a payment that has not been persisted yet. */
    public static PaymentId newId() {
        return new PaymentId(UUID.randomUUID());
    }

    public static PaymentId of(String value) {
        return new PaymentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}