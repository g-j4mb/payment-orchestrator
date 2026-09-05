package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.command.ProcessWebhookCommand;
import com.j4mb.payment_orchestrator.payments.application.port.in.ProcessWebhookUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort.GatewayWebhookEvent;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an asynchronous provider notification to the payment it concerns.
 *
 * <p>Providers redeliver webhooks freely, so this must be safe to run twice. Rather than tracking
 * delivery ids, it leans on the aggregate: a transition the payment has already made is rejected by
 * the domain, and that rejection is treated here as "already applied" rather than as an error.
 */
@Service
public class ProcessWebhookService implements ProcessWebhookUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessWebhookService.class);

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayResolver gatewayResolver;
    private final DomainEventPublisherPort eventPublisher;
    private final Clock clock;

    public ProcessWebhookService(
            PaymentRepositoryPort paymentRepository,
            PaymentGatewayResolver gatewayResolver,
            DomainEventPublisherPort eventPublisher,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gatewayResolver = gatewayResolver;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void process(ProcessWebhookCommand command) {
        PaymentGatewayPort gateway = gatewayResolver.resolve(command.provider());
        Optional<GatewayWebhookEvent> parsed = gateway.parseWebhook(command.rawPayload(), command.headers());
        if (parsed.isEmpty()) {
            log.debug("Ignoring {} webhook of an unhandled type", command.provider());
            return;
        }

        GatewayWebhookEvent event = parsed.get();
        Optional<Payment> found = paymentRepository.findByProviderReference(command.provider(), event.reference());
        if (found.isEmpty()) {
            log.warn("Received {} webhook for unknown reference {}", command.provider(), event.reference());
            return;
        }

        Payment payment = found.get();
        try {
            apply(payment, event);
        } catch (InvalidPaymentStateTransitionException ex) {
            log.debug("Ignoring already-applied {} webhook for payment {}", event.type(), payment.id());
            return;
        }

        Payment saved = paymentRepository.save(payment);
        eventPublisher.publishAll(payment.pullDomainEvents());
        log.info("Applied {} webhook to payment {}", event.type(), saved.id());
    }

    private void apply(Payment payment, GatewayWebhookEvent event) {
        switch (event.type()) {
            case AUTHORIZED -> payment.markAuthorized(event.reference(), clock.instant());
            case CAPTURED -> payment.capture(
                    event.amount() != null ? event.amount() : payment.capturableAmount(), clock.instant());
            case REFUNDED -> payment.refund(
                    event.amount() != null ? event.amount() : payment.refundableAmount(), clock.instant());
            case VOIDED -> payment.markVoided(clock.instant());
            case FAILED -> payment.markFailed(event.rawStatus(), clock.instant());
        }
    }
}