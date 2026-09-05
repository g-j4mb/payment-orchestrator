/**
 * Shared kernel: minimal, framework-free building blocks reused by every bounded context.
 *
 * <p>Nothing in this package may depend on Spring, JPA, or any bounded context. Keep it small —
 * anything that only one context needs belongs in that context instead.
 */
package com.j4mb.payment_orchestrator.common;