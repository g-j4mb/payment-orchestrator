package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.command.VoidPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.in.VoidPaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Releases an authorization before any funds are captured. */
@Service
public class VoidPaymentService implements VoidPaymentUseCase {

    private static final String OPERATION = "void";

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayResolver gatewayResolver;
    private final IdempotencyCheckService idempotencyCheck;
    private final DomainEventPublisherPort eventPublisher;
    private final Clock clock;

    public VoidPaymentService(
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

    @Override
    @Transactional
    public PaymentResult voidPayment(VoidPaymentCommand command) {
        Optional<Payment> replayed = idempotencyCheck.claim(command.idempotencyKey(), OPERATION).map(this::require);
        if (replayed.isPresent()) {
            return PaymentResult.from(replayed.get());
        }

        // One release path for every failure between claiming the key and succeeding — see the note
        // in RefundPaymentService for why a partial release strands the key.
        Payment payment;
        try {
            payment = requireForUpdate(command.paymentId());

            // Checked before the provider releases the authorization, for the same reason as capture.
            payment.ensureVoidable();

            PaymentGatewayPort gateway = gatewayResolver.resolve(payment.provider());
            PaymentGatewayPort.GatewayOperation operation = gateway.voidAuthorization(payment, command.reason());
            if (!operation.successful()) {
                throw new PaymentDeclinedException(payment.id(), operation.failureReason());
            }

            payment.markVoided(clock.instant());
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
     * Locks the payment for the rest of the transaction, so a concurrent void — or a capture racing a
     * void — cannot both act on the same authorization.
     */
    private Payment requireForUpdate(PaymentId paymentId) {
        return paymentRepository
                .findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}