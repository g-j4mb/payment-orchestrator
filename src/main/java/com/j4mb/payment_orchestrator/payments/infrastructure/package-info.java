/**
 * Infrastructure layer: the adapters that connect this context to the outside world.
 *
 * <p>{@code adapter.in} drives the application's inbound ports (REST, webhooks); {@code adapter.out}
 * implements its outbound ports (persistence, provider gateways, event publishing). Everything here
 * depends inward on {@code application} and {@code domain}, and nothing depends on it — swapping an
 * adapter never touches a use case.
 */
package com.j4mb.payment_orchestrator.payments.infrastructure;