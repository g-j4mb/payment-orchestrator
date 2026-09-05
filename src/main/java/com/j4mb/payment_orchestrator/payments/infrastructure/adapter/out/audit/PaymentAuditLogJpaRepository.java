package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the append-only audit log. */
public interface PaymentAuditLogJpaRepository extends JpaRepository<PaymentAuditLogEntry, UUID> {

    List<PaymentAuditLogEntry> findByPaymentIdOrderByOccurredAtAsc(UUID paymentId);
}
