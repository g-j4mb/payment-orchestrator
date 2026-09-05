/**
 * The <strong>payments</strong> bounded context.
 *
 * <p><b>Ubiquitous language.</b> A <em>Payment</em> is money a merchant collects from a payer
 * through one external <em>provider</em>. It is <em>authorized</em> (funds reserved), then
 * <em>captured</em> (funds taken), or <em>voided</em> before capture. Captured money can be
 * <em>refunded</em>, in full or in part. A <em>purchase</em> (sale) is an authorization captured
 * immediately. Each provider identifies the payment by its own <em>provider reference</em>. Callers
 * make requests safely repeatable with an <em>idempotency key</em>.
 *
 * <p><b>Boundary.</b> Provider integrations (Stripe, Adyen, Checkout.com) belong to this context as
 * outbound adapters, not as a context of their own: they carry no independent lifecycle or language
 * and exist solely to satisfy this context's gateway port. Split them out only if provider
 * onboarding/configuration (credentials, fee schedules, merchant-provider linking) becomes a domain
 * in its own right.
 *
 * <p><b>Structure.</b> {@code domain} (framework-free model) &larr; {@code application} (use cases
 * and ports) &larr; {@code infrastructure} (adapters). Dependencies point inward only.
 */
package com.j4mb.payment_orchestrator.payments;