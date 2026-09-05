package com.j4mb.payment_orchestrator.common;

import java.time.Instant;

/**
 * Marker interface implemented by every domain event raised inside an aggregate.
 *
 * <p>Events are published through an outbound port after the aggregate is persisted, which is what
 * drives audit logging today and can drive external event streaming later.
 */
public interface DomainEvent {

    /** When the event occurred, as recorded by the aggregate that raised it. */
    Instant occurredAt();
}