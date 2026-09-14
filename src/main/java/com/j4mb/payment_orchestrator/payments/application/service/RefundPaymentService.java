package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.command.RefundPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.in.RefundPaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.service.RefundPolicy;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Returns captured funds to the payer.
 *
 * <p>Eligibility is decided by {@link RefundPolicy} before the provider is called, so a refund the
 * domain forbids never reaches the network.
 */
@Service
public class RefundPaymentService implements RefundPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentService.class);
    private static final String OPERATION = "refund";

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayResolver gatewayResolver;
    private final IdempotencyCheckService idempotencyCheck;
    private final DomainEventPublisherPort eventPublisher;
    private final RefundPolicy refundPolicy;
    private final Clock clock;

    /** See {@code AuthorizePaymentService} for why this is a {@code TransactionTemplate}. */
    private final TransactionTemplate requiresNewTransaction;

    public RefundPaymentService(
            PaymentRepositoryPort paymentRepository,
            PaymentGatewayResolver gatewayResolver,
            IdempotencyCheckService idempotencyCheck,
            DomainEventPublisherPort eventPublisher,
            RefundPolicy refundPolicy,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.gatewayResolver = gatewayResolver;
        this.idempotencyCheck = idempotencyCheck;
        this.eventPublisher = eventPublisher;
        this.refundPolicy = refundPolicy;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public PaymentResult refund(RefundPaymentCommand command) {
        Optional<Payment> replayed = idempotencyCheck.claim(command.idempotencyKey(), OPERATION).map(this::require);
        if (replayed.isPresent()) {
            return PaymentResult.from(replayed.get());
        }

        Payment payment;
        Money amount;
        try {
            payment = requireForUpdate(command.paymentId());
            amount = command.amount().orElseGet(payment::refundableAmount);
            // Runs before the provider is asked to return money: a refund the policy would reject
            // must never reach the gateway. RefundPolicy itself enforces the aggregate's own
            // invariants before layering anything further on top, so this one call covers both —
            // including rejecting a second attempt while REFUND_PENDING (see beginRefundAttempt).
            refundPolicy.validate(payment, amount);
        } catch (RuntimeException ex) {
            idempotencyCheck.abandon(command.idempotencyKey());
            throw ex;
        }

        // A fresh key per attempt, not payment.id(): unlike authorize, a payment can be refunded more
        // than once over its life, so each attempt needs its own Stripe idempotency key.
        payment.beginRefundAttempt(amount, command.reason(), UUID.randomUUID().toString(), clock.instant());

        // Committed in its own transaction before the provider is ever called — see
        // AuthorizePaymentService for why. Survives a crash mid-call.
        Payment checkpointed = requiresNewTransaction.execute(status -> {
            Payment saved = paymentRepository.save(payment);
            idempotencyCheck.complete(command.idempotencyKey(), saved.id());
            eventPublisher.publishAll(payment.pullDomainEvents());
            return saved;
        });

        PaymentGatewayPort gateway = gatewayResolver.resolve(checkpointed.provider());
        PaymentGatewayPort.GatewayOperation operation;
        try {
            operation = gateway.refund(
                    checkpointed,
                    checkpointed.pendingRefundAmount().orElseThrow(),
                    checkpointed.pendingRefundReason().orElse(null));
        } catch (RuntimeException ex) {
            log.warn(
                    "{} refund call for {} did not complete; remains pending for reconciliation",
                    checkpointed.provider(),
                    checkpointed.id(),
                    ex);
            return PaymentResult.from(checkpointed);
        }

        if (!operation.successful()) {
            // A clean decline is a known, definitive outcome, unlike an ambiguous gateway failure —
            // there is nothing to reconcile. Revert the pending attempt and free the idempotency key
            // so a retry starts a genuinely new attempt rather than replaying stale pending state.
            checkpointed.cancelRefundPending(clock.instant());
            paymentRepository.save(checkpointed);
            eventPublisher.publishAll(checkpointed.pullDomainEvents());
            idempotencyCheck.abandon(command.idempotencyKey());
            throw new PaymentDeclinedException(checkpointed.id(), operation.failureReason());
        }

        checkpointed.resolveRefundPending(clock.instant());
        Payment saved = paymentRepository.save(checkpointed);
        eventPublisher.publishAll(checkpointed.pullDomainEvents());
        return PaymentResult.from(saved);
    }

    /** Replay lookup: the operation already finished, so no lock is needed. */
    private Payment require(PaymentId paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    /**
     * Locks the payment for the rest of the transaction, so a concurrent refund cannot read the same
     * refundable balance and return the money a second time.
     */
    private Payment requireForUpdate(PaymentId paymentId) {
        return paymentRepository
                .findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
