package com.j4mb.payment_orchestrator.payments.application.exception;

import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;

/**
 * Raised when an idempotency key is already in flight for another request.
 *
 * <p>A key whose original request has <em>completed</em> is not a conflict — that replay returns the
 * original result instead.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(IdempotencyKey key) {
        super("a request with idempotency key '%s' is already in progress".formatted(key));
    }
}