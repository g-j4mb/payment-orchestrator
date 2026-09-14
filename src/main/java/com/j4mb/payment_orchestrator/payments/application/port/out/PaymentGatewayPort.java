package com.j4mb.payment_orchestrator.payments.application.port.out;

import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound port for talking to an external payment provider.
 *
 * <p>This is the strategy interface: one implementation per {@link ProviderType}, resolved at call
 * time by {@code PaymentGatewayResolver}. Adding a provider means adding an implementation — nothing
 * in the domain or application layer changes.
 *
 * <p>Implementations own everything provider-specific: HTTP transport, credentials, payload shapes,
 * webhook signature verification. They translate results into the small vocabulary declared here so
 * the rest of the context never learns a provider's dialect.
 */
public interface PaymentGatewayPort {

    /** The provider this adapter speaks for. */
    ProviderType provider();

    /**
     * Reserves funds at the provider.
     *
     * @param captureMode when {@code AUTOMATIC}, the provider is asked to capture immediately
     */
    GatewayAuthorization authorize(
            Payment payment, String paymentMethodToken, CaptureMode captureMode);

    /** Takes previously authorized funds. */
    GatewayOperation capture(Payment payment, Money amount);

    /** Returns captured funds to the payer. */
    GatewayOperation refund(Payment payment, Money amount, String reason);

    /** Releases an authorization that has not been captured. */
    GatewayOperation voidAuthorization(Payment payment, String reason);

    /**
     * Verifies and parses a raw notification from this provider.
     *
     * @return the parsed event, or empty if the payload is a type this context does not act on
     * @throws com.j4mb.payment_orchestrator.payments.application.exception.WebhookVerificationException
     *     if the signature does not verify
     */
    Optional<GatewayWebhookEvent> parseWebhook(String rawPayload, Map<String, String> headers);

    /** Outcome of an authorization: the provider's reference plus whether it also captured. */
    record GatewayAuthorization(
            ProviderReference reference, boolean captured, boolean successful, String rawStatus, String failureReason) {

        public static GatewayAuthorization authorized(ProviderReference reference, String rawStatus) {
            return new GatewayAuthorization(reference, false, true, rawStatus, null);
        }

        public static GatewayAuthorization captured(ProviderReference reference, String rawStatus) {
            return new GatewayAuthorization(reference, true, true, rawStatus, null);
        }

        public static GatewayAuthorization declined(
                ProviderReference reference, String rawStatus, String failureReason) {
            return new GatewayAuthorization(reference, false, false, rawStatus, failureReason);
        }
    }

    /** Outcome of a capture, refund, or void. */
    record GatewayOperation(boolean successful, String rawStatus, String failureReason) {

        public static GatewayOperation succeeded(String rawStatus) {
            return new GatewayOperation(true, rawStatus, null);
        }

        public static GatewayOperation failed(String rawStatus, String failureReason) {
            return new GatewayOperation(false, rawStatus, failureReason);
        }
    }

    /**
     * A provider notification, normalized to this context's vocabulary.
     *
     * @param localPaymentId this context's own {@code Payment} id, when the provider object carries
     *     it back (e.g. Stripe metadata set at creation time) — null when the provider gives no way
     *     to attach one. Lets {@code ProcessWebhookService} correlate an event even when {@code
     *     reference} was never captured locally, such as a payment that crashed before a synchronous
     *     response was ever received.
     */
    record GatewayWebhookEvent(
            ProviderReference reference,
            Type type,
            Money amount,
            String rawEventId,
            String rawStatus,
            String localPaymentId) {

        public enum Type {
            AUTHORIZED,
            CAPTURED,
            REFUNDED,
            VOIDED,
            FAILED
        }
    }
}