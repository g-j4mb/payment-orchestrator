package com.j4mb.payment_orchestrator.payments.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j4mb.payment_orchestrator.payments.application.command.ProcessWebhookCommand;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayWebhookEvent;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProcessWebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ProviderReference REFERENCE = new ProviderReference("pi_123");

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static Payment paymentIn(PaymentId id, PaymentStatus status, Money captured, Money refunded) {
        return paymentIn(id, REFERENCE, status, captured, refunded);
    }

    private static Payment paymentIn(
            PaymentId id, ProviderReference reference, PaymentStatus status, Money captured, Money refunded) {
        boolean pending = status == PaymentStatus.AUTHORIZATION_PENDING || status == PaymentStatus.REFUND_PENDING;
        boolean refundPending = status == PaymentStatus.REFUND_PENDING;
        Payment payment = Payment.rehydrate(
                id,
                ProviderType.STRIPE,
                reference,
                usd("100.00"),
                captured,
                refunded,
                status,
                new IdempotencyKey("original-key-" + id),
                "tok_visa",
                CaptureMode.MANUAL,
                null,
                0,
                pending ? NOW : null,
                refundPending ? usd("40.00") : null,
                refundPending ? "requested_by_customer" : null,
                refundPending ? "attempt-1" : null,
                NOW,
                NOW);
        payment.pullDomainEvents();
        return payment;
    }

    private static ProcessWebhookCommand command() {
        return new ProcessWebhookCommand(ProviderType.STRIPE, "{}", Map.of());
    }

    private PaymentRepositoryPort paymentRepository;
    private PaymentGatewayResolver gatewayResolver;
    private DomainEventPublisherPort eventPublisher;
    private PaymentGatewayPort gateway;
    private ProcessWebhookService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepositoryPort.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        eventPublisher = mock(DomainEventPublisherPort.class);
        gateway = mock(PaymentGatewayPort.class);
        service = new ProcessWebhookService(paymentRepository, gatewayResolver, eventPublisher, CLOCK);

        when(gatewayResolver.resolve(ProviderType.STRIPE)).thenReturn(gateway);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void unhandledWebhookType_doesNothing() {
        when(gateway.parseWebhook(any(), any())).thenReturn(Optional.empty());

        service.process(command());

        verify(paymentRepository, never()).findByProviderReference(any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void webhookForAnUnknownReferenceAndNoLocalPaymentId_doesNothing() {
        GatewayWebhookEvent event = new GatewayWebhookEvent(
                REFERENCE, GatewayWebhookEvent.Type.AUTHORIZED, null, "evt_1", "authorized", null);
        when(gateway.parseWebhook(any(), any())).thenReturn(Optional.of(event));
        when(paymentRepository.findByProviderReference(ProviderType.STRIPE, REFERENCE)).thenReturn(Optional.empty());

        service.process(command());

        verify(paymentRepository, never()).save(any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void webhookForAnUnknownReference_fallsBackToLocalPaymentIdMetadata() {
        PaymentId id = PaymentId.newId();
        // AUTHORIZATION_PENDING: providerReference was never recorded locally — exactly the case a
        // crash mid-authorize-call leaves behind — so only the metadata id can find it.
        Payment payment = paymentIn(id, PaymentStatus.AUTHORIZATION_PENDING, usd("0.00"), usd("0.00"));
        GatewayWebhookEvent event = new GatewayWebhookEvent(
                REFERENCE, GatewayWebhookEvent.Type.AUTHORIZED, null, "evt_1", "authorized", id.value().toString());
        when(gateway.parseWebhook(any(), any())).thenReturn(Optional.of(event));
        when(paymentRepository.findByProviderReference(ProviderType.STRIPE, REFERENCE)).thenReturn(Optional.empty());
        when(paymentRepository.findById(id)).thenReturn(Optional.of(payment));

        service.process(command());

        verify(paymentRepository).save(argThat(p -> p.status() == PaymentStatus.AUTHORIZED));
        verify(eventPublisher).publishAll(argThat(events -> !events.isEmpty()));
    }

    @Nested
    class ApplyingAnEvent {

        private void arrange(Payment payment, GatewayWebhookEvent event) {
            when(gateway.parseWebhook(any(), any())).thenReturn(Optional.of(event));
            when(paymentRepository.findByProviderReference(ProviderType.STRIPE, REFERENCE))
                    .thenReturn(Optional.of(payment));
        }

        @Test
        void authorized_movesACreatedPaymentToAuthorized() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.CREATED, usd("0.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.AUTHORIZED, null, "evt_1", "authorized", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository).save(argThat(p -> p.status() == PaymentStatus.AUTHORIZED));
            verify(eventPublisher).publishAll(argThat(events -> !events.isEmpty()));
        }

        @Test
        void authorized_alsoResolvesAnAuthorizationPendingPayment() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.AUTHORIZATION_PENDING, usd("0.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.AUTHORIZED, null, "evt_1", "authorized", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository).save(argThat(p -> p.status() == PaymentStatus.AUTHORIZED));
        }

        @Test
        void captured_withExplicitAmount_capturesThatAmount() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.AUTHORIZED, usd("0.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.CAPTURED, usd("40.00"), "evt_2", "captured", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository)
                    .save(argThat(p -> p.status() == PaymentStatus.PARTIALLY_CAPTURED
                            && p.capturedAmount().equals(usd("40.00"))));
        }

        @Test
        void captured_withNoAmount_capturesTheFullAuthorizedBalance() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.AUTHORIZED, usd("0.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.CAPTURED, null, "evt_3", "captured", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository)
                    .save(argThat(p -> p.status() == PaymentStatus.CAPTURED
                            && p.capturedAmount().equals(usd("100.00"))));
        }

        @Test
        void captured_whileStillAuthorizationPending_authorizesThenCaptures() {
            // The auto-capture case: the synchronous authorize response was lost, but Stripe did
            // process it, immediate capture included — payment_intent.succeeded arrives first.
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.AUTHORIZATION_PENDING, usd("0.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.CAPTURED, usd("100.00"), "evt_2", "succeeded", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository)
                    .save(argThat(p -> p.status() == PaymentStatus.CAPTURED
                            && p.capturedAmount().equals(usd("100.00"))));
        }

        @Test
        void refunded_withExplicitAmount_refundsThatAmount() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.CAPTURED, usd("100.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.REFUNDED, usd("25.00"), "evt_4", "refunded", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository)
                    .save(argThat(p -> p.status() == PaymentStatus.PARTIALLY_REFUNDED
                            && p.refundedAmount().equals(usd("25.00"))));
        }

        @Test
        void refunded_withNoAmount_refundsTheFullCapturedBalance() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.CAPTURED, usd("100.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.REFUNDED, null, "evt_5", "refunded", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository)
                    .save(argThat(p -> p.status() == PaymentStatus.REFUNDED
                            && p.refundedAmount().equals(usd("100.00"))));
        }

        @Test
        void refunded_confirmsOurOwnInFlightRefundAttempt() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.REFUND_PENDING, usd("100.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.REFUNDED, usd("999.00"), "evt_4", "succeeded", null);
            arrange(payment, event);

            service.process(command());

            // resolveRefundPending() applies the amount recorded at beginRefundAttempt() time (40.00),
            // not whatever the event happens to carry — the event only confirms the attempt succeeded.
            verify(paymentRepository)
                    .save(argThat(p -> p.status() == PaymentStatus.PARTIALLY_REFUNDED
                            && p.refundedAmount().equals(usd("40.00"))));
        }

        @Test
        void voided_releasesAnAuthorizedPayment() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.AUTHORIZED, usd("0.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.VOIDED, null, "evt_6", "voided", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository).save(argThat(p -> p.status() == PaymentStatus.VOIDED));
        }

        @Test
        void failed_marksANonTerminalPaymentFailedWithTheRawStatusAsReason() {
            PaymentId id = PaymentId.newId();
            Payment payment = paymentIn(id, PaymentStatus.CREATED, usd("0.00"), usd("0.00"));
            GatewayWebhookEvent event = new GatewayWebhookEvent(
                    REFERENCE, GatewayWebhookEvent.Type.FAILED, null, "evt_7", "issuer_rejected", null);
            arrange(payment, event);

            service.process(command());

            verify(paymentRepository)
                    .save(argThat(p -> p.status() == PaymentStatus.FAILED
                            && p.failureReason().orElseThrow().equals("issuer_rejected")));
        }
    }

    @Test
    void alreadyAppliedWebhook_isIgnoredWithoutPersistingOrPublishing() {
        PaymentId id = PaymentId.newId();
        // Already AUTHORIZED: re-applying an AUTHORIZED webhook must be a no-op, not an error, since
        // providers redeliver webhooks freely.
        Payment payment = paymentIn(id, PaymentStatus.AUTHORIZED, usd("0.00"), usd("0.00"));
        GatewayWebhookEvent event = new GatewayWebhookEvent(
                REFERENCE, GatewayWebhookEvent.Type.AUTHORIZED, null, "evt_1", "authorized", null);
        when(gateway.parseWebhook(any(), any())).thenReturn(Optional.of(event));
        when(paymentRepository.findByProviderReference(ProviderType.STRIPE, REFERENCE)).thenReturn(Optional.of(payment));

        service.process(command());

        verify(paymentRepository, never()).save(any());
        verify(eventPublisher, never()).publishAll(any());
    }
}
