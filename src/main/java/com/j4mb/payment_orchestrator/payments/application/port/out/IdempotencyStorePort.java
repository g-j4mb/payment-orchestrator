package com.j4mb.payment_orchestrator.payments.application.port.out;

import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import java.util.Optional;

/**
 * Outbound port recording which idempotency keys have already been used, and for what.
 *
 * <p>The store must reject a duplicate reservation atomically — two concurrent requests carrying the
 * same key must not both succeed. A unique constraint on the key is what makes {@link #reserve}
 * reliable under concurrency.
 */
public interface IdempotencyStorePort {

    /**
     * Claims a key for an operation.
     *
     * @return {@code true} if this caller claimed it, {@code false} if it was already taken
     */
    boolean reserve(IdempotencyKey key, String operation);

    /** Records which payment the completed operation produced, so a replay can return it. */
    void recordResult(IdempotencyKey key, PaymentId paymentId);

    /** Finds the payment an earlier request with this key produced, if any. */
    Optional<PaymentId> findResult(IdempotencyKey key);

    /** Releases a claim whose operation failed, so the caller may retry with the same key. */
    void release(IdempotencyKey key);
}