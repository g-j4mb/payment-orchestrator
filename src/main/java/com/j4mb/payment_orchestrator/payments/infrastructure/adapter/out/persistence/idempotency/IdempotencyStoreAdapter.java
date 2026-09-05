package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.idempotency;

import com.j4mb.payment_orchestrator.payments.application.port.out.IdempotencyStorePort;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbound adapter implementing {@link IdempotencyStorePort} against the database.
 *
 * <p>{@link #reserve} runs in its own transaction: the claim must survive even when the surrounding
 * use case rolls back, and a duplicate-key violation must not poison the caller's transaction.
 */
@Component
public class IdempotencyStoreAdapter implements IdempotencyStorePort {

    private final IdempotencyRecordJpaRepository repository;
    private final Clock clock;

    public IdempotencyStoreAdapter(IdempotencyRecordJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(IdempotencyKey key, String operation) {
        if (repository.existsById(key.value())) {
            return false;
        }
        try {
            repository.saveAndFlush(new IdempotencyRecordJpaEntity(key.value(), operation, clock.instant()));
            return true;
        } catch (DataIntegrityViolationException ex) {
            // Another request claimed the key between the check and the insert.
            return false;
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResult(IdempotencyKey key, PaymentId paymentId) {
        repository.findById(key.value()).ifPresent(record -> {
            record.setPaymentId(paymentId.value());
            repository.save(record);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentId> findResult(IdempotencyKey key) {
        return repository
                .findById(key.value())
                .map(IdempotencyRecordJpaEntity::getPaymentId)
                .map(PaymentId::new);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(IdempotencyKey key) {
        repository.deleteById(key.value());
    }
}
