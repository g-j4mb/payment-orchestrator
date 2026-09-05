package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.command.AuthorizePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.in.AuthorizePaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorizes a payment at the chosen provider, capturing immediately when the caller asked for a
 * purchase (sale).
 */
@Service
public class AuthorizePaymentService implements AuthorizePaymentUseCase {

    private static final String OPERATION = "authorize";

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayResolver gatewayResolver;
    private final IdempotencyCheckService idempotencyCheck;
    private final DomainEventPublisherPort eventPublisher;
    private final Clock clock;

    public AuthorizePaymentService(
            PaymentRepositoryPort paymentRepository,
            PaymentGatewayResolver gatewayResolver,
            IdempotencyCheckService idempotencyCheck,
            DomainEventPublisherPort eventPublisher,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gatewayResolver = gatewayResolver;
        this.idempotencyCheck = idempotencyCheck;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>{@code noRollbackFor} is deliberate and load-bearing.</b> A decline is a business
     * outcome, not a failure: the FAILED payment and its idempotency record must both survive so a
     * retry with the same key returns the original decline instead of a "payment not found". Spring
     * rolls back on any {@code RuntimeException} by default, which would discard exactly the record
     * this method just wrote — so the exception stays (it is how the outcome is reported) and the
     * rollback is what gets turned off.
     */
    @Override
    @Transactional(noRollbackFor = PaymentDeclinedException.class)
    public PaymentResult authorize(AuthorizePaymentCommand command) {
        Optional<Payment> replayed = idempotencyCheck
                .claim(command.idempotencyKey(), OPERATION)
                .map(paymentId -> paymentRepository
                        .findById(paymentId)
                        .orElseThrow(() -> new PaymentNotFoundException(paymentId)));
        if (replayed.isPresent()) {
            Payment original = replayed.get();
            // A replay must reproduce the original outcome exactly, declines included. Returning a
            // FAILED payment normally here would report the same decline as a success on every retry.
            if (original.status() == PaymentStatus.FAILED) {
                throw new PaymentDeclinedException(
                        original.id(), original.failureReason().orElse(null));
            }
            return PaymentResult.from(original);
        }

        Instant now = clock.instant();
        Payment payment = Payment.initiate(command.provider(), command.amount(), command.idempotencyKey(), now);
        PaymentGatewayPort gateway = gatewayResolver.resolve(command.provider());

        PaymentGatewayPort.GatewayAuthorization authorization;
        try {
            authorization = gateway.authorize(payment, command.paymentMethodToken(), command.captureMode());
        } catch (RuntimeException ex) {
            idempotencyCheck.abandon(command.idempotencyKey());
            throw ex;
        }

        if (!authorization.successful()) {
            // Recorded, not discarded: see the noRollbackFor note on this method.
            payment.markFailed(authorization.failureReason(), clock.instant());
            persist(payment, command.idempotencyKey());
            throw new PaymentDeclinedException(payment.id(), authorization.failureReason());
        }

        payment.markAuthorized(authorization.reference(), clock.instant());
        if (authorization.captured()) {
            payment.capture(payment.authorizedAmount(), clock.instant());
        }
        return PaymentResult.from(persist(payment, command.idempotencyKey()));
    }

    private Payment persist(Payment payment, IdempotencyKey key) {
        Payment saved = paymentRepository.save(payment);
        idempotencyCheck.complete(key, saved.id());
        eventPublisher.publishAll(payment.pullDomainEvents());
        return saved;
    }
}