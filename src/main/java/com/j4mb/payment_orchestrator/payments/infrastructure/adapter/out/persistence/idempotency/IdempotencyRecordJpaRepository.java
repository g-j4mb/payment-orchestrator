package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link IdempotencyRecordJpaEntity}. */
public interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordJpaEntity, String> {}
