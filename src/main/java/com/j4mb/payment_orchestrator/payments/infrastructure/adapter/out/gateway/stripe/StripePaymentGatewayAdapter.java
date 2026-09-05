package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.stripe;

import com.j4mb.payment_orchestrator.payments.application.command.AuthorizePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of {@link PaymentGatewayPort}.
 *
 * <p>Everything Stripe-specific belongs behind this class: PaymentIntents, its amount encoding
 * (minor units as an integer), its idempotency header, and its {@code Stripe-Signature} scheme. The
 * rest of the application only ever sees the port's vocabulary.
 *
 * <p>Backs off when {@code payments.gateway.mock.enabled=true}, so exactly one adapter ever claims
 * {@code STRIPE}.
 */
@Component
@ConditionalOnProperty(
        prefix = "payments.gateway.mock",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class StripePaymentGatewayAdapter implements PaymentGatewayPort {

    private final StripeProperties properties;

    public StripePaymentGatewayAdapter(StripeProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.STRIPE;
    }

    @Override
    public GatewayAuthorization authorize(
            Payment payment, String paymentMethodToken, AuthorizePaymentCommand.CaptureMode captureMode) {
        // TODO: POST /v1/payment_intents with amount in minor units, confirm=true,
        // capture_method = automatic|manual, and the payment's idempotency key as the
        // Idempotency-Key header. Map the returned status to authorized/captured/declined.
        throw new UnsupportedOperationException("Stripe authorize is not implemented yet");
    }

    @Override
    public GatewayOperation capture(Payment payment, Money amount) {
        // TODO: POST /v1/payment_intents/{id}/capture with amount_to_capture in minor units.
        throw new UnsupportedOperationException("Stripe capture is not implemented yet");
    }

    @Override
    public GatewayOperation refund(Payment payment, Money amount, String reason) {
        // TODO: POST /v1/refunds with payment_intent and amount in minor units.
        throw new UnsupportedOperationException("Stripe refund is not implemented yet");
    }

    @Override
    public GatewayOperation voidAuthorization(Payment payment, String reason) {
        // TODO: POST /v1/payment_intents/{id}/cancel with cancellation_reason.
        throw new UnsupportedOperationException("Stripe void is not implemented yet");
    }

    @Override
    public Optional<GatewayWebhookEvent> parseWebhook(String rawPayload, Map<String, String> headers) {
        // TODO: verify the Stripe-Signature header against properties.webhookSecret() over the raw
        // body, then map payment_intent.* / charge.refunded events onto GatewayWebhookEvent.Type.
        // Throw WebhookVerificationException when the signature does not check out.
        throw new UnsupportedOperationException("Stripe webhook parsing is not implemented yet");
    }
}
