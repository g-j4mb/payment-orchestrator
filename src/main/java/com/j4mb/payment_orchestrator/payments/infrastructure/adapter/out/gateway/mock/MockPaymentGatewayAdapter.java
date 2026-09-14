package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.mock;

import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory stand-in for a real provider, so the whole payment flow can be exercised without a
 * network call or provider credentials.
 *
 * <p>One instance is registered per {@link ProviderType} by {@code MockGatewayConfig}, so requests
 * name real providers ({@code "provider": "STRIPE"}) and routing, idempotency, persistence, and
 * audit logging all run exactly as they will in production. Only the outermost hop is faked.
 *
 * <p><b>The mock holds no state.</b> The {@code Payment} aggregate is the record of what happened;
 * a mock that kept its own copy could disagree with it and turn a domain bug into a mock bug.
 *
 * <p><b>Webhook signatures are not verified here</b> — see {@link #parseWebhook}. That is safe
 * because this adapter never runs in production, and it is enforced rather than assumed: the
 * configuration that registers it refuses to start outside a mock-enabled profile.
 */
public class MockPaymentGatewayAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGatewayAdapter.class);

    /** A payment method token starting with this is declined, to exercise the decline path. */
    private static final String DECLINE_PREFIX = "decline";

    /** A token starting with this throws, to exercise the gateway-unreachable path. */
    private static final String ERROR_PREFIX = "error";

    private final ProviderType provider;
    private final ObjectMapper objectMapper;
    private final Duration latency;

    public MockPaymentGatewayAdapter(ProviderType provider, ObjectMapper objectMapper, Duration latency) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.latency = latency == null ? Duration.ZERO : latency;
    }

    /**
     * Simulates provider round-trip time.
     *
     * <p>Worth having because the interesting concurrency bugs only appear while a provider call is
     * in flight — with an instant mock, the window where two requests can both act on the same
     * payment is too small to hit deliberately.
     */
    private void simulateLatency() {
        if (latency.isZero() || latency.isNegative()) {
            return;
        }
        try {
            Thread.sleep(latency.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while simulating %s latency".formatted(provider), ex);
        }
    }

    @Override
    public ProviderType provider() {
        return provider;
    }

    @Override
    public GatewayAuthorization authorize(
            Payment payment, String paymentMethodToken, CaptureMode captureMode) {
        simulateLatency();
        String token = paymentMethodToken == null ? "" : paymentMethodToken.toLowerCase(Locale.ROOT);

        if (token.startsWith(ERROR_PREFIX)) {
            // Not a decline — a failure to reach the provider at all. The use case must release the
            // idempotency key for this, so it is worth being able to trigger deliberately.
            throw new IllegalStateException("mock %s gateway is unreachable".formatted(provider));
        }

        if (token.startsWith(DECLINE_PREFIX)) {
            log.info("Mock {} declining payment {}", provider, payment.id());
            return GatewayAuthorization.declined(newReference(), "mock_declined", "Card was declined (mock)");
        }

        ProviderReference reference = newReference();
        boolean captured = captureMode == CaptureMode.AUTOMATIC;
        log.info(
                "Mock {} authorized payment {} as {} ({})",
                provider,
                payment.id(),
                reference,
                captured ? "purchase" : "authorization only");

        return captured
                ? GatewayAuthorization.captured(reference, "mock_captured")
                : GatewayAuthorization.authorized(reference, "mock_authorized");
    }

    @Override
    public GatewayOperation capture(Payment payment, Money amount) {
        simulateLatency();
        log.info("Mock {} captured {} on payment {}", provider, amount, payment.id());
        return GatewayOperation.succeeded("mock_captured");
    }

    @Override
    public GatewayOperation refund(Payment payment, Money amount, String reason) {
        simulateLatency();
        log.info("Mock {} refunded {} on payment {} ({})", provider, amount, payment.id(), reason);
        return GatewayOperation.succeeded("mock_refunded");
    }

    @Override
    public GatewayOperation voidAuthorization(Payment payment, String reason) {
        simulateLatency();
        log.info("Mock {} voided payment {} ({})", provider, payment.id(), reason);
        return GatewayOperation.succeeded("mock_voided");
    }

    /**
     * Parses a webhook body with <b>no signature verification</b>, so notifications can be replayed
     * by hand with plain {@code curl}.
     *
     * <p>Expected body — {@code amount}, {@code currency}, and {@code local_payment_id} are optional,
     * and when omitted the use case applies the full remaining capturable or refundable amount (and,
     * for {@code local_payment_id}, falls back to correlating by {@code reference} alone):
     *
     * <pre>{@code
     * {"reference": "mock_stripe_1a2b3c4d", "type": "CAPTURED", "amount": "49.99", "currency": "USD",
     *  "local_payment_id": "b6e2..."}
     * }</pre>
     *
     * <p>Real adapters must verify the provider's signature over the raw body here and throw
     * {@code WebhookVerificationException} when it does not check out.
     */
    @Override
    public Optional<GatewayWebhookEvent> parseWebhook(String rawPayload, Map<String, String> headers) {
        JsonNode body = objectMapper.readTree(rawPayload);

        String reference = body.path("reference").asString(null);
        String type = body.path("type").asString(null);
        if (reference == null || reference.isBlank() || type == null || type.isBlank()) {
            log.warn("Mock {} webhook is missing 'reference' or 'type'; ignoring", provider);
            return Optional.empty();
        }

        GatewayWebhookEvent.Type eventType;
        try {
            eventType = GatewayWebhookEvent.Type.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // An unrecognized type is ignored rather than rejected: that is how a real provider
            // sending an event we do not act on must behave.
            log.debug("Mock {} webhook of unhandled type '{}'; ignoring", provider, type);
            return Optional.empty();
        }

        log.info("Mock {} webhook: {} for {}", provider, eventType, reference);
        return Optional.of(new GatewayWebhookEvent(
                new ProviderReference(reference),
                eventType,
                amountOf(body),
                "mock_" + UUID.randomUUID(),
                "mock",
                body.path("local_payment_id").asString(null)));
    }

    /** Returns null when the body carries no amount — the use case reads that as "the full amount". */
    private Money amountOf(JsonNode body) {
        JsonNode amount = body.path("amount");
        JsonNode currency = body.path("currency");
        if (amount.isMissingNode() || amount.isNull() || currency.isMissingNode() || currency.isNull()) {
            return null;
        }
        return new Money(new BigDecimal(amount.asString()), Currency.getInstance(currency.asString()));
    }

    private ProviderReference newReference() {
        return new ProviderReference("mock_%s_%s"
                .formatted(
                        provider.name().toLowerCase(Locale.ROOT),
                        UUID.randomUUID().toString().substring(0, 8)));
    }
}
