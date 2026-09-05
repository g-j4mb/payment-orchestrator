package com.j4mb.payment_orchestrator.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for aggregate roots: carries the identity type and collects domain events raised
 * during a unit of work.
 *
 * <p>Application services pull the events with {@link #pullDomainEvents()} after saving the
 * aggregate and hand them to the event publisher port.
 *
 * @param <ID> the aggregate's identity type
 */
public abstract class AggregateRoot<ID> {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    /** The aggregate's identity. */
    public abstract ID id();

    /** Records an event raised by a state change on this aggregate. */
    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /** Returns the events raised so far and clears them, so they are published exactly once. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> pulled = List.copyOf(domainEvents);
        domainEvents.clear();
        return pulled;
    }

    /** Read-only view of the pending events, for assertions and debugging. */
    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }
}