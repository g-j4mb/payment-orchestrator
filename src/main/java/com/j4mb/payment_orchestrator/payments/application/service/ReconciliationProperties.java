package com.j4mb.payment_orchestrator.payments.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on how long {@link ReconcilePendingPaymentsService} keeps retrying a pending payment.
 *
 * <p>Both limits are ours to define — Stripe prescribes no cadence for this. Whichever trips first
 * stops automatic retries, so a payment does not get reconciled forever.
 */
@ConfigurationProperties(prefix = "payments.reconciliation")
public record ReconciliationProperties(Integer maxAttempts, Duration maxAge) {

    public ReconciliationProperties {
        maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        maxAge = maxAge == null ? Duration.ofDays(1) : maxAge;
    }
}
