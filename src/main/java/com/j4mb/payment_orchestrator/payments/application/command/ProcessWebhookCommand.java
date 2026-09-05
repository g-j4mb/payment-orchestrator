package com.j4mb.payment_orchestrator.payments.application.command;

import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.Map;
import java.util.Objects;

/**
 * A raw provider notification, exactly as received.
 *
 * <p>The payload stays unparsed here on purpose: only the provider's own adapter knows how to verify
 * its signature and read its format, so parsing belongs behind the gateway port, not in front of it.
 */
public record ProcessWebhookCommand(ProviderType provider, String rawPayload, Map<String, String> headers) {

    public ProcessWebhookCommand {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
    }
}