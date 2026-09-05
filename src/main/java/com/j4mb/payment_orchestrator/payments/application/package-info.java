/**
 * Application layer: use cases and the ports they talk through.
 *
 * <p>{@code port.in} declares what the outside world can ask of this context; {@code port.out}
 * declares what this context needs from the outside world. Both are framework-free and expressed in
 * domain vocabulary. Adapters in {@code infrastructure} implement the outbound ports and drive the
 * inbound ones — this layer never depends on them.
 */
package com.j4mb.payment_orchestrator.payments.application;