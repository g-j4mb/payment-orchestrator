package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.adyen;

import com.j4mb.payment_orchestrator.payments.application.command.AuthorizePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.exception.ProviderNotImplementedException;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adyen implementation of {@link PaymentGatewayPort} — registered but not yet built out.
 *
 * <p>It exists now so the port is designed against three concrete providers rather than one, and so
 * routing to Adyen fails with a clear "not implemented" rather than "unknown provider".
 *
 * <p>Backs off when {@code payments.gateway.mock.enabled=true} — that is how the Adyen route can be
 * exercised end to end before the integration lands.
 */
@Component
@ConditionalOnProperty(
        prefix = "payments.gateway.mock",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class AdyenPaymentGatewayAdapter implements PaymentGatewayPort {

    @Override
    public ProviderType provider() {
        return ProviderType.ADYEN;
    }

    @Override
    public GatewayAuthorization authorize(
            Payment payment, String paymentMethodToken, AuthorizePaymentCommand.CaptureMode captureMode) {
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
