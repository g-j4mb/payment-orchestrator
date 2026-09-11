package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.idempotency;

import com.j4mb.payment_orchestrator.payments.application.port.out.IdempotencyStorePort;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    /**
     * Runs just the insert attempt in its own transaction, separate from {@link #reserve}'s own
     * method body.
     *
     * <p>This is load-bearing, not a style choice: if the insert instead ran under {@code reserve}'s
     * own {@code @Transactional(REQUIRES_NEW)} annotation, a failed flush would mark that transaction
     * rollback-only at the JPA-provider level regardless of whether the exception is caught here.
     * Catching it would then return normally into a transaction Spring refuses to commit, throwing
     * {@code UnexpectedRollbackException} instead — the very failure this method exists to avoid.
     * Running the insert through a separate {@link TransactionTemplate} means that by the time the
     * exception reaches the catch block below, the failed transaction has already rolled back and
     * is no longer current, so recovering here is safe.
     */
    private final TransactionTemplate requiresNewTransaction;

    public IdempotencyStoreAdapter(
            IdempotencyRecordJpaRepository repository, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean reserve(IdempotencyKey key, String operation) {
        if (repository.existsById(key.value())) {
            return false;
        }
        try {
            requiresNewTransaction.executeWithoutResult(status -> repository.saveAndFlush(
                    new IdempotencyRecordJpaEntity(key.value(), operation, clock.instant())));
            return true;
        } catch (DataIntegrityViolationException ex) {
            // Another request claimed the key between the check and the insert.
            return false;
        }
    }
/*
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(IdempotencyKey key,String operation){
        if (repository.existsById(key.value())) {
            return false;
        }
        try {
            repository.saveAndFlush(new IdempotencyRecordJpaEntity(key.value(),operation,clock.instant()));
            return true;
        } catch (DataIntegrityViolationException ex) {
            // Another request claimed the key between the check and the insert.
            return false;
        }
    }

 */
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
