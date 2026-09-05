/**
 * Tactical DDD model for the payments context: the {@code Payment} aggregate, its value objects,
 * domain events, domain services, and domain exceptions.
 *
 * <p>This package is deliberately framework-free — no Spring, no JPA, no HTTP. Persistence and
 * provider concerns are expressed as ports in the application layer and satisfied by adapters in
 * the infrastructure layer. {@code ArchitectureTest} enforces this.
 */
package com.j4mb.payment_orchestrator.payments.domain;