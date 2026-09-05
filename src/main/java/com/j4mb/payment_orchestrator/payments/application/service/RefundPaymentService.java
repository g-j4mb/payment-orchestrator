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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns captured funds to the payer.
 *
 * <p>Eligibility is decided by {@link RefundPolicy} before the provider is called, so a refund the
 * domain forbids never reaches the network.
 */
@Service
public class RefundPaymentService implements RefundPaymentUseCase {

    private static final String OPERATION = "refund";

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayResolver gatewayResolver;
    private final IdempotencyCheckService idempotencyCheck;
    private final DomainEventPublisherPort eventPublisher;
    private final RefundPolicy refundPolicy;
    private final Clock clock;

    public RefundPaymentService(
            PaymentRepositoryPort paymentRepository,
            PaymentGatewayResolver gatewayResolver,
            IdempotencyCheckService idempotencyCheck,
            DomainEventPublisherPort eventPublisher,
            RefundPolicy refundPolicy,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gatewayResolver = gatewayResolver;
        this.idempotencyCheck = idempotencyCheck;
        this.eventPublisher = eventPublisher;
        this.refundPolicy = refundPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentResult refund(RefundPaymentCommand command) {
        Optional<Payment> replayed = idempotencyCheck.claim(command.idempotencyKey(), OPERATION).map(this::require);
        if (replayed.isPresent()) {
            return PaymentResult.from(replayed.get());
        }

        // Everything between claiming the key and succeeding runs under one release path. Releasing
        // only on gateway failures would strand the key on any other error — an unknown payment or a
        // policy rejection — and a stranded key makes every retry a 409 forever.
        Payment payment;
        try {
            payment = requireForUpdate(command.paymentId());
            Money amount = command.amount().orElseGet(payment::refundableAmount);

            // Two checks, two concerns: the aggregate's own invariants (state and balance), then any
            // policy layered on top. Both run before the provider is asked to return money.
            payment.ensureRefundable(amount);
            refundPolicy.validate(payment, amount);

            PaymentGatewayPort gateway = gatewayResolver.resolve(payment.provider());
            PaymentGatewayPort.GatewayOperation operation = gateway.refund(payment, amount, command.reason());
            if (!operation.successful()) {
                throw new PaymentDeclinedException(payment.id(), operation.failureReason());
            }

            payment.refund(amount, clock.instant());
        } catch (RuntimeException ex) {
            idempotencyCheck.abandon(command.idempotencyKey());
            throw ex;
        }

        Payment saved = paymentRepository.save(payment);
        idempotencyCheck.complete(command.idempotencyKey(), saved.id());
        eventPublisher.publishAll(payment.pullDomainEvents());
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