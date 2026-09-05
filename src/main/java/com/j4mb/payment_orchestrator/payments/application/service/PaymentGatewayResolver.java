package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.exception.UnsupportedProviderException;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Picks the gateway adapter for a provider.
 *
 * <p>Every {@link PaymentGatewayPort} bean is injected and indexed by the provider it declares, so a
 * new provider becomes reachable by adding an adapter — no registration list to update here.
 */
@Component
public class PaymentGatewayResolver {

    private final Map<ProviderType, PaymentGatewayPort> gatewaysByProvider;

    public PaymentGatewayResolver(List<PaymentGatewayPort> gateways) {
        Map<ProviderType, PaymentGatewayPort> index = new EnumMap<>(ProviderType.class);
        for (PaymentGatewayPort gateway : gateways) {
            PaymentGatewayPort previous = index.put(gateway.provider(), gateway);
            if (previous != null) {
                throw new IllegalStateException(
                        "two gateway adapters claim provider %s: %s and %s"
                                .formatted(
                                        gateway.provider(),
                                        previous.getClass().getName(),
                                        gateway.getClass().getName()));
            }
        }
        this.gatewaysByProvider = Map.copyOf(index);
    }

    /**
     * @throws UnsupportedProviderException if no adapter is registered for the provider
     */
    public PaymentGatewayPort resolve(ProviderType provider) {
        PaymentGatewayPort gateway = gatewaysByProvider.get(provider);
        if (gateway == null) {
            throw new UnsupportedProviderException(provider);
        }
        return gateway;
    }
}