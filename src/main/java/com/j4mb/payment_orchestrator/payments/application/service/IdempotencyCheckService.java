package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.exception.IdempotencyConflictException;
import com.j4mb.payment_orchestrator.payments.application.port.out.IdempotencyStorePort;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Guards mutating use cases against duplicate requests.
 *
 * <p>This is an application service, not a domain service: deciding whether a key has been seen
 * requires a store, and the domain model must stay free of infrastructure.
 *
 * <p>The contract is three-state — a key is either fresh (proceed), already completed (return the
 * original result), or claimed by an in-flight request (conflict).
 */
@Service
public class IdempotencyCheckService {

    private final IdempotencyStorePort idempotencyStore;

    public IdempotencyCheckService(IdempotencyStorePort idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * Claims {@code key} for {@code operation}.
     *
     * @return the payment produced by an earlier request with this key, or empty if the caller may
     *     proceed
     * @throws IdempotencyConflictException if another request holds the key and has not finished
     */
    public Optional<PaymentId> claim(IdempotencyKey key, String operation) {
        Optional<PaymentId> previous = idempotencyStore.findResult(key);
        if (previous.isPresent()) {
            return previous;
        }
        if (!idempotencyStore.reserve(key, operation)) {
            // Reserved but no result recorded: the original request is still running.
            return idempotencyStore
                    .findResult(key)
                    .map(Optional::of)
                    .orElseThrow(() -> new IdempotencyConflictException(key));
        }
        return Optional.empty();
    }

    /** Records the outcome so a later replay of the same key returns it. */
    public void complete(IdempotencyKey key, PaymentId paymentId) {
        idempotencyStore.recordResult(key, paymentId);
    }

    /** Frees the key after a failure, so the caller can retry with it. */
    public void abandon(IdempotencyKey key) {
        idempotencyStore.release(key);
    }
}