package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One claimed idempotency key.
 *
 * <p>The primary key <em>is</em> the idempotency key, so a duplicate reservation fails at the
 * database rather than in application code — that is what makes the guard hold under concurrency.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecordJpaEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "operation", nullable = false, length = 32)
    private String operation;

    /** Null while the original request is still running. */
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecordJpaEntity() {
        // required by JPA
    }

    public IdempotencyRecordJpaEntity(String idempotencyKey, String operation, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOperation() {
        return operation;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
