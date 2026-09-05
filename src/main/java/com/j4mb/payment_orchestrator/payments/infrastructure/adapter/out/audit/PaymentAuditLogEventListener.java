package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.audit;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentAuthorized;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentCaptured;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentFailed;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentRefunded;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentVoided;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes every payment domain event to the audit log.
 *
 * <p>Runs <em>before</em> commit so the audit row lands in the same transaction as the state change
 * it describes: either both are recorded or neither is. Listening after commit would be cheaper but
 * could leave a captured payment with no audit trail if the write failed.
 */
@Component
public class PaymentAuditLogEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentAuditLogEventListener.class);

    private final PaymentAuditLogJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentAuditLogEventListener(
            PaymentAuditLogJpaRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(DomainEvent event) {
        UUID paymentId = paymentIdOf(event);
        if (paymentId == null) {
            // Warn, not debug: an unmapped event means a real state change went unaudited, which is
            // exactly how PaymentFailed stayed missing from this log. Silence hid it.
            log.warn(
                    "No audit row written — {} is not mapped in paymentIdOf()",
                    event.getClass().getSimpleName());
            return;
        }
        repository.save(new PaymentAuditLogEntry(
                paymentId,
                event.getClass().getSimpleName(),
                serialize(event),
                event.occurredAt(),
                clock.instant()));
    }

    private UUID paymentIdOf(DomainEvent event) {
        return switch (event) {
            case PaymentAuthorized e -> e.paymentId().value();
            case PaymentCaptured e -> e.paymentId().value();
            case PaymentRefunded e -> e.paymentId().value();
            case PaymentVoided e -> e.paymentId().value();
            case PaymentFailed e -> e.paymentId().value();
            default -> null;
        };
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            // An unserializable payload must not block the payment — record what we can.
            log.warn("Could not serialize {} for the audit log", event.getClass().getSimpleName(), ex);
            return "{\"error\":\"payload could not be serialized\"}";
        }
    }
}
