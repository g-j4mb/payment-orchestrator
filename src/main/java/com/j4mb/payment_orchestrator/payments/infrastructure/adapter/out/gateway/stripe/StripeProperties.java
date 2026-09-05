package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.stripe;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe connection settings.
 *
 * <p>Secrets are read from the environment, never committed — see {@code .env} and
 * {@code application.yaml}.
 */
@ConfigurationProperties(prefix = "payments.stripe")
public record StripeProperties(
        String apiKey, String webhookSecret, String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public StripeProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.stripe.com" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
    }

    /** Whether enough is configured to actually call Stripe. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
