package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.mock;

import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers a {@link MockPaymentGatewayAdapter} for every provider when
 * {@code payments.gateway.mock.enabled=true}.
 *
 * <p>The real adapters carry the inverse condition, so exactly one adapter ever claims a given
 * provider — {@code PaymentGatewayResolver} fails at startup if that were ever violated.
 *
 * <p>Registering a mock per provider rather than one shared mock keeps the resolver's routing under
 * test: a request naming {@code ADYEN} is still dispatched by provider, it just lands on a mock.
 */
@Configuration
@ConditionalOnProperty(prefix = "payments.gateway.mock", name = "enabled", havingValue = "true")
public class MockGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayConfig.class);

    /** Profiles this must never be active in. Mock gateways move no money and verify no signatures. */
    private static final List<String> FORBIDDEN_PROFILES = List.of("prod", "production");

    private final Environment environment;

    /** Simulated provider round-trip time; {@code payments.gateway.mock.latency}, e.g. {@code 2s}. */
    @Value("${payments.gateway.mock.latency:0s}")
    private Duration latency;

    public MockGatewayConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Refuses to start where a mock gateway would be dangerous.
     *
     * <p>A misplaced flag would otherwise mean every payment silently succeeds without any money
     * moving — the kind of failure that is invisible until reconciliation. Better to not boot.
     */
    @PostConstruct
    void rejectForbiddenProfiles() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        List<String> violations = FORBIDDEN_PROFILES.stream().filter(active::contains).toList();
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "payments.gateway.mock.enabled=true is not allowed with the %s profile active — "
                                    .formatted(violations)
                            + "mock gateways accept every payment without moving money");
        }
        log.warn(
                "MOCK PAYMENT GATEWAYS ARE ACTIVE for {} — no real provider is being called",
                Arrays.toString(ProviderType.values()));
    }

    @Bean
    public MockPaymentGatewayAdapter mockStripeGateway(ObjectMapper objectMapper) {
        return new MockPaymentGatewayAdapter(ProviderType.STRIPE, objectMapper, latency);
    }

    @Bean
    public MockPaymentGatewayAdapter mockAdyenGateway(ObjectMapper objectMapper) {
        return new MockPaymentGatewayAdapter(ProviderType.ADYEN, objectMapper, latency);
    }

    @Bean
    public MockPaymentGatewayAdapter mockCheckoutGateway(ObjectMapper objectMapper) {
        return new MockPaymentGatewayAdapter(ProviderType.CHECKOUT, objectMapper, latency);
    }
}
