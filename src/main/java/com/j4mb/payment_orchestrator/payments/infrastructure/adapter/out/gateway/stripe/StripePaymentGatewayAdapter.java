package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.gateway.stripe;

import com.j4mb.payment_orchestrator.payments.application.exception.WebhookVerificationException;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import com.stripe.exception.CardException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of {@link PaymentGatewayPort}, backed by the official {@code stripe-java}
 * SDK.
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

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGatewayAdapter.class);

    private final StripeProperties properties;

    /**
     * Carries the API key, base URL, and timeouts common to every call. {@code baseUrl} is what
     * makes this adapter testable against a stub server instead of real Stripe — see {@link
     * StripeProperties#baseUrl()}.
     */
    private final RequestOptions baseRequestOptions;

    /**
     * Shared across all four outbound calls: they share one failure mode (Stripe being degraded), so
     * one breaker per external dependency is the right grain, not one per operation. A short-circuited
     * call throws {@code CallNotPermittedException} — a plain {@link RuntimeException} — which already
     * falls into the same "ambiguous, let reconciliation retry later" catch blocks the application
     * layer uses for a genuine network failure.
     */
    private final CircuitBreaker circuitBreaker;

    public StripePaymentGatewayAdapter(StripeProperties properties, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.properties = properties;
        this.baseRequestOptions = RequestOptions.builder()
                .setApiKey(properties.apiKey())
                .setBaseUrl(properties.baseUrl())
                .setConnectTimeout((int) properties.connectTimeout().toMillis())
                .setReadTimeout((int) properties.readTimeout().toMillis())
                .build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("stripe");
    }

    @Override
    public ProviderType provider() {
        return ProviderType.STRIPE;
    }

    @Override
    public GatewayAuthorization authorize(Payment payment, String paymentMethodToken, CaptureMode captureMode) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toStripeAmount(payment.authorizedAmount()))
                .setCurrency(lowercaseCurrencyCode(payment.authorizedAmount()))
                .setPaymentMethod(paymentMethodToken)
                .addPaymentMethodType("card")
                .setConfirm(true)
                .setCaptureMethod(
                        captureMode == CaptureMode.AUTOMATIC
                                ? PaymentIntentCreateParams.CaptureMethod.AUTOMATIC
                                : PaymentIntentCreateParams.CaptureMethod.MANUAL)
                // Echoed back on every webhook for this object, so ProcessWebhookService can
                // correlate a payment that crashed before ever recording Stripe's own reference.
                .putMetadata("payment_id", payment.id().value().toString())
                .build();
        // Payment's own id, not its client-facing idempotencyKey: it is durably checkpointed (see
        // AuthorizePaymentService.markAuthorizationPending) before this call is ever made, so it is
        // stable across a reconciliation retry — Stripe rejects reusing a key with different params,
        // and a retry sends identical params under this same id.
        // toBuilder() deliberately excludes the API key from the copy; toBuilderFullCopy() is the
        // variant that actually preserves it alongside the rest of baseRequestOptions.
        RequestOptions options = baseRequestOptions
                .toBuilderFullCopy()
                .setIdempotencyKey(payment.id().value().toString())
                .build();

        PaymentIntent intent;
        try {
            intent = runProtected(() -> PaymentIntent.create(params, options));
        } catch (CardException ex) {
            return GatewayAuthorization.declined(declineReference(ex, payment), ex.getCode(), messageOf(ex));
        } catch (StripeException ex) {
            throw new IllegalStateException("Stripe authorize call failed", ex);
        }

        ProviderReference reference = new ProviderReference(intent.getId());
        return switch (intent.getStatus()) {
            case "succeeded" -> GatewayAuthorization.captured(reference, intent.getStatus());
            case "requires_capture" -> GatewayAuthorization.authorized(reference, intent.getStatus());
            // Chiefly "requires_action" (e.g. 3D Secure) and "requires_payment_method": neither fits
            // this port's authorized/captured/declined vocabulary, and this adapter does not yet
            // support redirect-based confirmation. Reporting it as a decline is honest about the
            // outcome — the payment does not go through on this attempt — without pretending to
            // support a flow that is not implemented.
            default -> GatewayAuthorization.declined(
                    reference, intent.getStatus(), "unexpected PaymentIntent status: " + intent.getStatus());
        };
    }

    @Override
    public GatewayOperation capture(Payment payment, Money amount) {
        String reference = referenceOf(payment);
        long amountToCapture = toStripeAmount(amount);
        return execute("capture", () -> {
            PaymentIntent intent = PaymentIntent.retrieve(reference, baseRequestOptions);
            PaymentIntentCaptureParams params =
                    PaymentIntentCaptureParams.builder().setAmountToCapture(amountToCapture).build();
            return intent.capture(params, baseRequestOptions).getStatus();
        });
    }

    @Override
    public GatewayOperation refund(Payment payment, Money amount, String reason) {
        RefundCreateParams.Builder builder = RefundCreateParams.builder()
                .setPaymentIntent(referenceOf(payment))
                .setAmount(toStripeAmount(amount))
                // Same correlation purpose as authorize()'s metadata — refund.created carries its own
                // object back on webhooks, distinct from the PaymentIntent's.
                .putMetadata("payment_id", payment.id().value().toString());
        // Stripe's `reason` is a closed 3-value enum, unlike this domain's free-text reason. A
        // matching value is sent structured; anything else is kept as metadata instead of being
        // silently dropped.
        Optional<RefundCreateParams.Reason> mappedReason = mapRefundReason(reason);
        if (mappedReason.isPresent()) {
            builder.setReason(mappedReason.get());
        } else if (reason != null && !reason.isBlank()) {
            builder.putMetadata("reason", reason);
        }
        RefundCreateParams params = builder.build();

        // The in-flight attempt's own key (see Payment.beginRefundAttempt) — a fresh one per attempt,
        // unlike authorize's once-per-aggregate key, since a payment can be refunded more than once
        // over its life. Reconciliation resends the same key stored here, so a retry replays Stripe's
        // cached response instead of creating a second Refund object.
        RequestOptions options = baseRequestOptions
                .toBuilderFullCopy()
                .setIdempotencyKey(payment.refundAttemptIdempotencyKey()
                        .orElseThrow(() -> new IllegalStateException(
                                "payment %s has no in-flight refund attempt to send".formatted(payment.id()))))
                .build();

        return execute("refund", () -> Refund.create(params, options).getStatus());
    }

    @Override
    public GatewayOperation voidAuthorization(Payment payment, String reason) {
        String reference = referenceOf(payment);
        PaymentIntentCancelParams.Builder builder = PaymentIntentCancelParams.builder();
        mapCancellationReason(reason).ifPresent(builder::setCancellationReason);
        PaymentIntentCancelParams params = builder.build();

        return execute("void", () -> {
            PaymentIntent intent = PaymentIntent.retrieve(reference, baseRequestOptions);
            return intent.cancel(params, baseRequestOptions).getStatus();
        });
    }

    @Override
    public Optional<GatewayWebhookEvent> parseWebhook(String rawPayload, Map<String, String> headers) {
        // WebhookController lower-cases every header name before this method ever sees it.
        String signature = headers.get("stripe-signature");
        Event event;
        try {
            event = Webhook.constructEvent(rawPayload, signature, properties.webhookSecret());
        } catch (SignatureVerificationException ex) {
            throw new WebhookVerificationException(ProviderType.STRIPE, ex.getMessage());
        }

        GatewayWebhookEvent.Type type = mapEventType(event.getType());
        if (type == null) {
            log.debug("Ignoring Stripe webhook of an unhandled type '{}'", event.getType());
            return Optional.empty();
        }

        StripeObject dataObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (type == GatewayWebhookEvent.Type.REFUNDED) {
            return dataObject instanceof Refund refund
                    ? Optional.of(refundWebhookEvent(event, refund))
                    : Optional.empty();
        }
        return dataObject instanceof PaymentIntent intent
                ? Optional.of(paymentIntentWebhookEvent(event, type, intent))
                : Optional.empty();
    }

    /**
     * Runs a Stripe call that returns a raw status string, translating outcomes the way every
     * mutating operation on this gateway needs to: a card decline is a business outcome
     * ({@link GatewayOperation#failed}), anything else wrong with the call (network, auth,
     * rate-limit, a malformed request) is the provider being unreachable or misbehaving, which the
     * application layer already knows how to react to when a gateway call throws.
     */
    private GatewayOperation execute(String operationName, StripeCall call) {
        try {
            return GatewayOperation.succeeded(runProtected(call::run));
        } catch (CardException ex) {
            return GatewayOperation.failed(ex.getCode(), messageOf(ex));
        } catch (StripeException ex) {
            throw new IllegalStateException("Stripe " + operationName + " call failed", ex);
        }
    }

    /**
     * Routes a Stripe call through the shared circuit breaker. A short-circuited call throws {@code
     * CallNotPermittedException} — an unchecked exception — which is deliberately let through
     * unwrapped: every caller already has a catch block for "the call did not happen; ambiguous, let
     * reconciliation or the caller's own retry handle it later."
     */
    private <T> T runProtected(StripeSupplier<T> call) throws StripeException {
        try {
            return circuitBreaker.executeCallable(call::get);
        } catch (StripeException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Stripe call failed", ex);
        }
    }

    @FunctionalInterface
    private interface StripeCall {
        String run() throws StripeException;
    }

    @FunctionalInterface
    private interface StripeSupplier<T> {
        T get() throws StripeException;
    }

    private GatewayWebhookEvent paymentIntentWebhookEvent(
            Event event, GatewayWebhookEvent.Type type, PaymentIntent intent) {
        // Only CAPTURED reads its event's amount (see ProcessWebhookService#apply); passing it for
        // the others would be inert, so it is left null there to avoid implying it means something.
        Money amount = type == GatewayWebhookEvent.Type.CAPTURED && intent.getAmountReceived() != null
                ? fromStripeAmount(intent.getAmountReceived(), intent.getCurrency())
                : null;
        return new GatewayWebhookEvent(
                new ProviderReference(intent.getId()),
                type,
                amount,
                event.getId(),
                intent.getStatus(),
                metadataPaymentId(intent.getMetadata()));
    }

    private GatewayWebhookEvent refundWebhookEvent(Event event, Refund refund) {
        return new GatewayWebhookEvent(
                new ProviderReference(refund.getPaymentIntent()),
                GatewayWebhookEvent.Type.REFUNDED,
                fromStripeAmount(refund.getAmount(), refund.getCurrency()),
                event.getId(),
                refund.getStatus(),
                metadataPaymentId(refund.getMetadata()));
    }

    /** The correlation id set at creation time — see {@code authorize}/{@code refund}. */
    private static String metadataPaymentId(Map<String, String> metadata) {
        return metadata != null ? metadata.get("payment_id") : null;
    }

    /**
     * @return null for a Stripe event type this context does not act on — {@code parseWebhook}
     *     turns that into {@code Optional.empty()}, exactly as a real provider notification this
     *     context ignores must be handled.
     */
    private static GatewayWebhookEvent.Type mapEventType(String stripeEventType) {
        return switch (stripeEventType) {
            case "payment_intent.amount_capturable_updated" -> GatewayWebhookEvent.Type.AUTHORIZED;
            case "payment_intent.succeeded" -> GatewayWebhookEvent.Type.CAPTURED;
            case "payment_intent.payment_failed" -> GatewayWebhookEvent.Type.FAILED;
            case "payment_intent.canceled" -> GatewayWebhookEvent.Type.VOIDED;
            // Not charge.refunded: its charge object only carries the cumulative amount_refunded,
            // not the incremental amount of this specific refund that ProcessWebhookService needs.
            case "refund.created" -> GatewayWebhookEvent.Type.REFUNDED;
            default -> null;
        };
    }

    private static Optional<RefundCreateParams.Reason> mapRefundReason(String reason) {
        if (reason == null) {
            return Optional.empty();
        }
        return switch (reason.trim().toLowerCase(Locale.ROOT)) {
            case "duplicate" -> Optional.of(RefundCreateParams.Reason.DUPLICATE);
            case "fraudulent" -> Optional.of(RefundCreateParams.Reason.FRAUDULENT);
            case "requested_by_customer" -> Optional.of(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
            default -> Optional.empty();
        };
    }

    private static Optional<PaymentIntentCancelParams.CancellationReason> mapCancellationReason(String reason) {
        if (reason == null) {
            return Optional.empty();
        }
        return switch (reason.trim().toLowerCase(Locale.ROOT)) {
            case "duplicate" -> Optional.of(PaymentIntentCancelParams.CancellationReason.DUPLICATE);
            case "fraudulent" -> Optional.of(PaymentIntentCancelParams.CancellationReason.FRAUDULENT);
            case "requested_by_customer" -> Optional.of(
                    PaymentIntentCancelParams.CancellationReason.REQUESTED_BY_CUSTOMER);
            case "abandoned" -> Optional.of(PaymentIntentCancelParams.CancellationReason.ABANDONED);
            default -> Optional.empty();
        };
    }

    /** Best-effort: a card decline still carries the PaymentIntent id when Stripe reports one. */
    private static ProviderReference declineReference(CardException ex, Payment payment) {
        PaymentIntent failedIntent = ex.getStripeError() != null ? ex.getStripeError().getPaymentIntent() : null;
        String id = failedIntent != null ? failedIntent.getId() : null;
        return new ProviderReference(id != null ? id : "declined_" + payment.id());
    }

    private static String messageOf(CardException ex) {
        return ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage();
    }

    private static String referenceOf(Payment payment) {
        return payment.providerReference()
                .orElseThrow(() -> new IllegalStateException(
                        "payment %s has no Stripe reference to act on".formatted(payment.id())))
                .value();
    }

    private static String lowercaseCurrencyCode(Money money) {
        return money.currency().getCurrencyCode().toLowerCase(Locale.ROOT);
    }

    /** Stripe amounts are integers in the currency's smallest unit — not always cents. */
    private static long toStripeAmount(Money money) {
        return money.amount()
                .movePointRight(money.currency().getDefaultFractionDigits())
                .longValueExact();
    }

    private static Money fromStripeAmount(long minorUnits, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode.toUpperCase(Locale.ROOT));
        BigDecimal amount = BigDecimal.valueOf(minorUnits).movePointLeft(currency.getDefaultFractionDigits());
        return new Money(amount, currency);
    }
}
