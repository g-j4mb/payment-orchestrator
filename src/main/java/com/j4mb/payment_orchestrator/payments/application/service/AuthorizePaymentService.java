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
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authorizes a payment at the chosen provider, capturing immediately when the caller asked for a
 * purchase (sale).
 */
@Service
public class AuthorizePaymentService implements AuthorizePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthorizePaymentService.class);
    private static final String OPERATION = "authorize";

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayResolver gatewayResolver;
    private final IdempotencyCheckService idempotencyCheck;
    private final DomainEventPublisherPort eventPublisher;
    private final Clock clock;

    /**
     * Commits the pre-call pending checkpoint independently of the surrounding transaction — see
     * {@link #authorize}. Self-invoking a local {@code @Transactional} method is not proxied, so this
     * uses the same {@code TransactionTemplate} approach as {@code IdempotencyStoreAdapter}.
     */
    private final TransactionTemplate requiresNewTransaction;

    public AuthorizePaymentService(
            PaymentRepositoryPort paymentRepository,
            PaymentGatewayResolver gatewayResolver,
            IdempotencyCheckService idempotencyCheck,
            DomainEventPublisherPort eventPublisher,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.gatewayResolver = gatewayResolver;
        this.idempotencyCheck = idempotencyCheck;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
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
        Payment payment = Payment.initiate(
                command.provider(),
                command.amount(),
                command.idempotencyKey(),
                command.paymentMethodToken(),
                command.captureMode(),
                now);
        payment.markAuthorizationPending(now);

        // Committed in its own transaction before the provider is ever called — survives a crash
        // mid-call, which a reactive "persist only after the exception" approach would not. The
        // client's idempotency key is completed here too, not after the gateway resolves: if the
        // process dies before the call even starts, a retry correctly sees "pending" instead of
        // racing a second attempt, and reconciliation makes the (still unattempted) call later.
        Payment checkpointed = requiresNewTransaction.execute(status -> {
            Payment saved = paymentRepository.save(payment);
            idempotencyCheck.complete(command.idempotencyKey(), saved.id());
            eventPublisher.publishAll(payment.pullDomainEvents());
            return saved;
        });

        PaymentGatewayPort gateway = gatewayResolver.resolve(command.provider());
        PaymentGatewayPort.GatewayAuthorization authorization;
        try {
            authorization = gateway.authorize(checkpointed, command.paymentMethodToken(), command.captureMode());
        } catch (RuntimeException ex) {
            log.warn(
                    "{} authorize call for {} did not complete; remains pending for reconciliation",
                    command.provider(),
                    checkpointed.id(),
                    ex);
            return PaymentResult.from(checkpointed);
        }

        if (!authorization.successful()) {
            // Recorded, not discarded: see the noRollbackFor note on this method.
            checkpointed.markFailed(authorization.failureReason(), clock.instant());
            Payment saved = paymentRepository.save(checkpointed);
            eventPublisher.publishAll(checkpointed.pullDomainEvents());
            throw new PaymentDeclinedException(checkpointed.id(), authorization.failureReason());
        }

        checkpointed.markAuthorized(authorization.reference(), clock.instant());
        if (authorization.captured()) {
            checkpointed.capture(checkpointed.authorizedAmount(), clock.instant());
        }
        Payment saved = paymentRepository.save(checkpointed);
        eventPublisher.publishAll(checkpointed.pullDomainEvents());
        return PaymentResult.from(saved);
    }
}
