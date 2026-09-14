package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PaymentJpaEntity}.
 *
 * <p>Infrastructure detail — the application layer talks to {@code PaymentRepositoryPort} instead,
 * which {@link PaymentPersistenceAdapter} implements on top of this.
 */
public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    /**
     * {@code SELECT … FOR UPDATE} — the inherited {@code findById} cannot carry a lock mode, so the
     * query is declared explicitly.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentJpaEntity p where p.id = :id")
    Optional<PaymentJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<PaymentJpaEntity> findByProviderAndProviderReference(String provider, String providerReference);

    Optional<PaymentJpaEntity> findByIdempotencyKey(String idempotencyKey);

    /** Backs both {@code findAuthorizationPendingOlderThan} and {@code findRefundPendingOlderThan}. */
    List<PaymentJpaEntity> findByStatusAndUpdatedAtBefore(String status, Instant threshold);
}
