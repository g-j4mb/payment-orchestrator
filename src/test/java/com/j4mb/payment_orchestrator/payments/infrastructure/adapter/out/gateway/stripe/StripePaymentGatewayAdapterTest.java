package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.stripe;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.application.exception.WebhookVerificationException;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayAuthorization;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayOperation;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayWebhookEvent;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exercises {@link StripePaymentGatewayAdapter} against a WireMock stub standing in for Stripe's
 * API — verifies the actual outgoing request (idempotency header, amounts) and response mapping,
 * without hitting real Stripe or needing an API key.
 */
class StripePaymentGatewayAdapterTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private static final String WEBHOOK_SECRET = "whsec_test_secret";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static Payment newCreatedPayment() {
        return Payment.initiate(
                ProviderType.STRIPE,
                usd("100.00"),
                new IdempotencyKey("key-" + System.nanoTime()),
                "pm_card_visa",
                CaptureMode.MANUAL,
                NOW);
    }

    private static Payment authorizedPayment(String reference) {
        Payment payment = newCreatedPayment();
        payment.markAuthorized(new ProviderReference(reference), NOW);
        payment.pullDomainEvents();
        return payment;
    }

    /** A captured payment with an in-flight refund attempt already checkpointed — see beginRefundAttempt. */
    private static Payment paymentWithPendingRefund(String reference, Money amount, String reason) {
        Payment payment = authorizedPayment(reference);
        payment.capture(usd("100.00"), NOW);
        payment.beginRefundAttempt(amount, reason, "refund-attempt-key", NOW);
        payment.pullDomainEvents();
        return payment;
    }

    private static String paymentIntentJson(String id, String status, long amount, long amountReceived, String currency) {
        return paymentIntentJson(id, status, amount, amountReceived, currency, null);
    }

    private static String paymentIntentJson(
            String id, String status, long amount, long amountReceived, String currency, String localPaymentId) {
        String metadata = localPaymentId == null ? "{}" : "{ \"payment_id\": \"" + localPaymentId + "\" }";
        return """
                {
                  "id": "%s",
                  "object": "payment_intent",
                  "status": "%s",
                  "amount": %d,
                  "amount_received": %d,
                  "currency": "%s",
                  "metadata": %s,
                  "livemode": false
                }
                """
                .formatted(id, status, amount, amountReceived, currency, metadata);
    }

    private static String cardDeclinedJson(String paymentIntentId, String declineCode, String message) {
        return """
                {
                  "error": {
                    "type": "card_error",
                    "code": "card_declined",
                    "decline_code": "%s",
                    "message": "%s",
                    "payment_intent": {
                      "id": "%s",
                      "object": "payment_intent",
                      "status": "requires_payment_method"
                    }
                  }
                }
                """
                .formatted(declineCode, message, paymentIntentId);
    }

    private static String paymentIntentEventJson(String eventId, String type, String paymentIntentJson) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "%s",
                  "data": { "object": %s }
                }
                """
                .formatted(eventId, com.stripe.Stripe.API_VERSION, type, paymentIntentJson);
    }

    private static String refundEventJson(String eventId, String refundId, String paymentIntentId, long amount, String currency) {
        return refundEventJson(eventId, refundId, paymentIntentId, amount, currency, null);
    }

    private static String refundEventJson(
            String eventId, String refundId, String paymentIntentId, long amount, String currency, String localPaymentId) {
        String metadata = localPaymentId == null ? "{}" : "{ \"payment_id\": \"" + localPaymentId + "\" }";
        return """
                {
                  "id": "%s",
                  "api_version": "%s",
                  "object": "event",
                  "type": "refund.created",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "refund",
                      "payment_intent": "%s",
                      "amount": %d,
                      "currency": "%s",
                      "status": "succeeded",
                      "metadata": %s
                    }
                  }
                }
                """
                .formatted(eventId, com.stripe.Stripe.API_VERSION, refundId, paymentIntentId, amount, currency, metadata);
    }

    /** Hand-rolled since the SDK does not publish its own test-signature helper. */
    private static String stripeSignature(String payload, long timestampSeconds, String secret) {
        try {
            String signedPayload = timestampSeconds + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hmac) {
                hex.append(String.format("%02x", b));
            }
            return "t=" + timestampSeconds + ",v1=" + hex;
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private StripePaymentGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        StripeProperties properties = new StripeProperties(
                "sk_test_dummy", WEBHOOK_SECRET, wireMock.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2));
        adapter = new StripePaymentGatewayAdapter(properties, CircuitBreakerRegistry.ofDefaults());
    }

    @Nested
    class Authorize {

        @Test
        void manualCapture_requiresCapture_returnsAuthorized() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_123", "requires_capture", 10000, 0, "usd"))));

            GatewayAuthorization result = adapter.authorize(newCreatedPayment(), "pm_card_visa", CaptureMode.MANUAL);

            assertThat(result.successful()).isTrue();
            assertThat(result.captured()).isFalse();
            assertThat(result.reference()).isEqualTo(new ProviderReference("pi_123"));
        }

        @Test
        void automaticCapture_succeeded_returnsCaptured() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_456", "succeeded", 10000, 10000, "usd"))));

            GatewayAuthorization result = adapter.authorize(newCreatedPayment(), "pm_card_visa", CaptureMode.AUTOMATIC);

            assertThat(result.successful()).isTrue();
            assertThat(result.captured()).isTrue();
        }

        @Test
        void cardDeclined_returnsDeclined() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(402)
                            .withHeader("Content-Type", "application/json")
                            .withBody(cardDeclinedJson("pi_789", "generic_decline", "Your card was declined."))));

            GatewayAuthorization result = adapter.authorize(newCreatedPayment(), "pm_card_visa", CaptureMode.MANUAL);

            assertThat(result.successful()).isFalse();
            assertThat(result.failureReason()).contains("declined");
        }

        @Test
        void sendsThePaymentsOwnIdAsTheStripeIdempotencyKey() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_999", "requires_capture", 10000, 0, "usd"))));
            Payment payment = newCreatedPayment();

            adapter.authorize(payment, "pm_card_visa", CaptureMode.MANUAL);

            // Not the client-facing idempotencyKey: the payment's own id is durably checkpointed
            // before this call is ever made, so it stays stable across a reconciliation retry.
            wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/payment_intents"))
                    .withHeader("Idempotency-Key", equalTo(payment.id().value().toString())));
        }

        @Test
        void sendsThePaymentsIdAsMetadata_forWebhookCorrelation() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_999", "requires_capture", 10000, 0, "usd"))));
            Payment payment = newCreatedPayment();

            adapter.authorize(payment, "pm_card_visa", CaptureMode.MANUAL);

            wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/payment_intents"))
                    .withRequestBody(containing("metadata[payment_id]="
                            + payment.id().value())));
        }

        @Test
        void serverError_propagatesRatherThanBeingTreatedAsADecline() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "error": { "type": "api_error", "message": "Internal server error." } }
                                    """)));

            assertThatThrownBy(() -> adapter.authorize(newCreatedPayment(), "pm_card_visa", CaptureMode.MANUAL))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void unexpectedStatus_isReportedAsDeclinedRatherThanMisrepresentingTheOutcome() {
            // e.g. "requires_action" (3D Secure) — not supported by this adapter yet.
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_321", "requires_action", 10000, 0, "usd"))));

            GatewayAuthorization result = adapter.authorize(newCreatedPayment(), "pm_card_visa", CaptureMode.MANUAL);

            assertThat(result.successful()).isFalse();
            assertThat(result.failureReason()).contains("requires_action");
        }
    }

    @Nested
    class Capture {

        @Test
        void success() {
            wireMock.stubFor(get(urlPathEqualTo("/v1/payment_intents/pi_123"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_123", "requires_capture", 10000, 0, "usd"))));
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_123/capture"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_123", "succeeded", 10000, 10000, "usd"))));

            GatewayOperation result = adapter.capture(authorizedPayment("pi_123"), usd("100.00"));

            assertThat(result.successful()).isTrue();
        }

        @Test
        void declinedOnCapture_returnsFailed() {
            wireMock.stubFor(get(urlPathEqualTo("/v1/payment_intents/pi_123"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_123", "requires_capture", 10000, 0, "usd"))));
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_123/capture"))
                    .willReturn(aResponse()
                            .withStatus(402)
                            .withHeader("Content-Type", "application/json")
                            .withBody(cardDeclinedJson("pi_123", "generic_decline", "The capture failed."))));

            GatewayOperation result = adapter.capture(authorizedPayment("pi_123"), usd("100.00"));

            assertThat(result.successful()).isFalse();
        }

        @Test
        void serverErrorOnCapture_propagatesRatherThanBeingTreatedAsADecline() {
            wireMock.stubFor(get(urlPathEqualTo("/v1/payment_intents/pi_123"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_123", "requires_capture", 10000, 0, "usd"))));
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_123/capture"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "error": { "type": "api_error", "message": "Internal server error." } }
                                    """)));

            assertThatThrownBy(() -> adapter.capture(authorizedPayment("pi_123"), usd("100.00")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void paymentWithNoProviderReference_throwsBeforeCallingStripe() {
            Payment neverAuthorized = newCreatedPayment();

            assertThatThrownBy(() -> adapter.capture(neverAuthorized, usd("100.00")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no Stripe reference");
        }
    }

    @Nested
    class Refund {

        @Test
        void success() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/refunds"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "id": "re_1", "object": "refund", "payment_intent": "pi_123",
                                      "amount": 4000, "currency": "usd", "status": "succeeded" }
                                    """)));
            Payment payment = paymentWithPendingRefund("pi_123", usd("40.00"), "requested_by_customer");

            GatewayOperation result = adapter.refund(payment, usd("40.00"), "requested_by_customer");

            assertThat(result.successful()).isTrue();
        }

        @Test
        void reasonNotMatchingStripesEnum_isKeptAsMetadataInstead() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/refunds"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "id": "re_2", "object": "refund", "payment_intent": "pi_123",
                                      "amount": 4000, "currency": "usd", "status": "succeeded" }
                                    """)));
            Payment payment = paymentWithPendingRefund("pi_123", usd("40.00"), "customer changed their mind");

            adapter.refund(payment, usd("40.00"), "customer changed their mind");

            wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/refunds"))
                    .withRequestBody(containing("metadata[reason]")));
        }

        @Test
        void sendsThePaymentsIdAsMetadata_forWebhookCorrelation() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/refunds"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "id": "re_3", "object": "refund", "payment_intent": "pi_123",
                                      "amount": 4000, "currency": "usd", "status": "succeeded" }
                                    """)));
            Payment payment = paymentWithPendingRefund("pi_123", usd("40.00"), "requested_by_customer");

            adapter.refund(payment, usd("40.00"), "requested_by_customer");

            wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/refunds"))
                    .withRequestBody(containing("metadata[payment_id]="
                            + payment.id().value())));
        }

        @Test
        void sendsTheInFlightAttemptsIdempotencyKey() {
            wireMock.stubFor(post(urlPathEqualTo("/v1/refunds"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "id": "re_4", "object": "refund", "payment_intent": "pi_123",
                                      "amount": 4000, "currency": "usd", "status": "succeeded" }
                                    """)));
            Payment payment = paymentWithPendingRefund("pi_123", usd("40.00"), "requested_by_customer");

            adapter.refund(payment, usd("40.00"), "requested_by_customer");

            wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/refunds"))
                    .withHeader("Idempotency-Key", equalTo("refund-attempt-key")));
        }

        @Test
        void noInFlightAttempt_throwsBeforeCallingStripe() {
            Payment neverBegunRefund = authorizedPayment("pi_123");

            assertThatThrownBy(() -> adapter.refund(neverBegunRefund, usd("40.00"), "requested_by_customer"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no in-flight refund attempt");
        }
    }

    @Nested
    class Void {

        @Test
        void success() {
            wireMock.stubFor(get(urlPathEqualTo("/v1/payment_intents/pi_123"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_123", "requires_capture", 10000, 0, "usd"))));
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_123/cancel"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("pi_123", "canceled", 10000, 0, "usd"))));

            GatewayOperation result = adapter.voidAuthorization(authorizedPayment("pi_123"), "requested_by_customer");

            assertThat(result.successful()).isTrue();
        }
    }

    @Nested
    class ParseWebhook {

        @Test
        void validSignature_paymentIntentSucceeded_mapsToCaptured() {
            String payload = paymentIntentEventJson(
                    "evt_1", "payment_intent.succeeded", paymentIntentJson("pi_123", "succeeded", 10000, 10000, "usd"));
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo(GatewayWebhookEvent.Type.CAPTURED);
            assertThat(result.get().reference()).isEqualTo(new ProviderReference("pi_123"));
            assertThat(result.get().amount()).isEqualTo(usd("100.00"));
        }

        @Test
        void validSignature_amountCapturableUpdated_mapsToAuthorizedWithNoAmount() {
            String payload = paymentIntentEventJson(
                    "evt_5",
                    "payment_intent.amount_capturable_updated",
                    paymentIntentJson("pi_222", "requires_capture", 10000, 0, "usd"));
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo(GatewayWebhookEvent.Type.AUTHORIZED);
            assertThat(result.get().amount()).isNull();
        }

        @Test
        void validSignature_paymentFailed_mapsToFailed() {
            String payload = paymentIntentEventJson(
                    "evt_6",
                    "payment_intent.payment_failed",
                    paymentIntentJson("pi_333", "requires_payment_method", 10000, 0, "usd"));
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo(GatewayWebhookEvent.Type.FAILED);
        }

        @Test
        void validSignature_paymentIntentCanceled_mapsToVoided() {
            String payload = paymentIntentEventJson(
                    "evt_7", "payment_intent.canceled", paymentIntentJson("pi_444", "canceled", 10000, 0, "usd"));
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo(GatewayWebhookEvent.Type.VOIDED);
        }

        @Test
        void validSignature_refundCreated_mapsToRefundedWithIncrementalAmount() {
            String payload = refundEventJson("evt_2", "re_1", "pi_123", 2500, "usd");
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo(GatewayWebhookEvent.Type.REFUNDED);
            assertThat(result.get().amount()).isEqualTo(usd("25.00"));
        }

        @Test
        void paymentIntentEvent_extractsLocalPaymentIdFromMetadata() {
            String paymentId = "b6e2f4b0-1234-4a1b-9c1d-000000000001";
            String payload = paymentIntentEventJson(
                    "evt_8",
                    "payment_intent.succeeded",
                    paymentIntentJson("pi_888", "succeeded", 10000, 10000, "usd", paymentId));
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().localPaymentId()).isEqualTo(paymentId);
        }

        @Test
        void refundEvent_extractsLocalPaymentIdFromMetadata() {
            String paymentId = "b6e2f4b0-1234-4a1b-9c1d-000000000002";
            String payload = refundEventJson("evt_9", "re_9", "pi_123", 2500, "usd", paymentId);
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().localPaymentId()).isEqualTo(paymentId);
        }

        @Test
        void noMetadata_localPaymentIdIsNull() {
            String payload = paymentIntentEventJson(
                    "evt_10", "payment_intent.succeeded", paymentIntentJson("pi_777", "succeeded", 10000, 10000, "usd"));
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            Optional<GatewayWebhookEvent> result = adapter.parseWebhook(payload, Map.of("stripe-signature", signature));

            assertThat(result).isPresent();
            assertThat(result.get().localPaymentId()).isNull();
        }

        @Test
        void invalidSignature_throwsWebhookVerificationException() {
            String payload = paymentIntentEventJson(
                    "evt_3", "payment_intent.succeeded", paymentIntentJson("pi_999", "succeeded", 5000, 5000, "usd"));

            assertThatThrownBy(() -> adapter.parseWebhook(payload, Map.of("stripe-signature", "t=1,v1=not-a-real-signature")))
                    .isInstanceOf(WebhookVerificationException.class);
        }

        @Test
        void unrecognizedEventType_returnsEmpty() {
            String payload = paymentIntentEventJson(
                    "evt_4", "payment_intent.created", paymentIntentJson("pi_555", "requires_payment_method", 5000, 0, "usd"));
            String signature = stripeSignature(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);

            assertThat(adapter.parseWebhook(payload, Map.of("stripe-signature", signature))).isEmpty();
        }
    }

    @Nested
    class CircuitBreakerBehavior {

        @Test
        void afterRepeatedFailures_shortCircuitsWithoutCallingStripeAgain() {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMinutes(1))
                    .build();
            StripeProperties properties = new StripeProperties(
                    "sk_test_dummy", WEBHOOK_SECRET, wireMock.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2));
            StripePaymentGatewayAdapter breakerAdapter =
                    new StripePaymentGatewayAdapter(properties, CircuitBreakerRegistry.of(config));
            wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "error": { "type": "api_error", "message": "Internal server error." } }
                                    """)));

            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> breakerAdapter.authorize(newCreatedPayment(), "pm_card_visa", CaptureMode.MANUAL))
                        .isInstanceOf(IllegalStateException.class);
            }
            wireMock.resetRequests();

            assertThatThrownBy(() -> breakerAdapter.authorize(newCreatedPayment(), "pm_card_visa", CaptureMode.MANUAL))
                    .isInstanceOf(CallNotPermittedException.class);
            wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v1/payment_intents")));
        }
    }
}
