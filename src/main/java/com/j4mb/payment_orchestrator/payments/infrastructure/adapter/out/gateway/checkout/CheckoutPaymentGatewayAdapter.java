package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.checkout;

import com.j4mb.payment_orchestrator.payments.application.exception.ProviderNotImplementedException;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Checkout.com implementation of {@link PaymentGatewayPort} — registered but not yet built out.
 *
 * <p>See {@code AdyenPaymentGatewayAdapter} for why the stub exists ahead of the integration, and
 * why it backs off when mock gateways are enabled.
 */
@Component
@ConditionalOnProperty(
        prefix = "payments.gateway.mock",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class CheckoutPaymentGatewayAdapter implements PaymentGatewayPort {

    @Override
    public ProviderType provider() {
        return ProviderType.CHECKOUT;
    }

    @Override
    public GatewayAuthorization authorize(
            Payment payment, String paymentMethodToken, CaptureMode captureMode) {
        throw new ProviderNotImplementedException(provider(), "authorize");
    }

    @Override
    public GatewayOperation capture(Payment payment, Money amount) {
        throw new ProviderNotImplementedException(provider(), "capture");
    }

    @Override
    public GatewayOperation refund(Payment payment, Money amount, String reason) {
        throw new ProviderNotImplementedException(provider(), "refund");
    }

    @Override
    public GatewayOperation voidAuthorization(Payment payment, String reason) {
        throw new ProviderNotImplementedException(provider(), "void");
    }

    @Override
    public Optional<GatewayWebhookEvent> parseWebhook(String rawPayload, Map<String, String> headers) {
        throw new ProviderNotImplementedException(provider(), "webhook");
    }
}
