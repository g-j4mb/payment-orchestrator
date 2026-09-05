package com.j4mb.payment_orchestrator.payments.application.port.out;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Outbound port for publishing domain events raised by an aggregate.
 *
 * <p>Today the only subscriber is the audit log. This is also the seam for external event streaming
 * later: an additional adapter can publish to a broker without any change to the application layer.
 */
public interface DomainEventPublisherPort {

    void publish(DomainEvent event);

    default void publishAll(@NonNull List<? extends DomainEvent> events) {
        events.forEach(this::publish);
    }
}