package com.j4mb.payment_orchestrator.payments.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j4mb.payment_orchestrator.payments.application.exception.UnsupportedProviderException;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentGatewayResolverTest {

    private static PaymentGatewayPort gatewayFor(ProviderType provider) {
        PaymentGatewayPort gateway = mock(PaymentGatewayPort.class);
        when(gateway.provider()).thenReturn(provider);
        return gateway;
    }

    @Test
    void resolvesTheGatewayRegisteredForEachProvider() {
        PaymentGatewayPort stripe = gatewayFor(ProviderType.STRIPE);
        PaymentGatewayPort adyen = gatewayFor(ProviderType.ADYEN);
        PaymentGatewayResolver resolver = new PaymentGatewayResolver(List.of(stripe, adyen));

        assertThat(resolver.resolve(ProviderType.STRIPE)).isSameAs(stripe);
        assertThat(resolver.resolve(ProviderType.ADYEN)).isSameAs(adyen);
    }

    @Test
    void resolvingAnUnregisteredProvider_throws() {
        PaymentGatewayResolver resolver = new PaymentGatewayResolver(List.of(gatewayFor(ProviderType.STRIPE)));

        assertThatThrownBy(() -> resolver.resolve(ProviderType.ADYEN))
                .isInstanceOf(UnsupportedProviderException.class)
                .hasMessageContaining("ADYEN");
    }

    @Test
    void noGatewaysRegistered_resolvingAnyProviderThrows() {
        PaymentGatewayResolver resolver = new PaymentGatewayResolver(List.of());

        assertThatThrownBy(() -> resolver.resolve(ProviderType.STRIPE))
                .isInstanceOf(UnsupportedProviderException.class);
    }

    @Test
    void twoAdaptersClaimingTheSameProvider_throwsAtConstruction() {
        List<PaymentGatewayPort> gateways =
                List.of(gatewayFor(ProviderType.STRIPE), gatewayFor(ProviderType.STRIPE));

        assertThatThrownBy(() -> new PaymentGatewayResolver(gateways))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE");
    }
}
