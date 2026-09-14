package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.port.out.DomainEventPublisherPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentGatewayPort;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backstop for authorize/refund attempts left {@code AUTHORIZATION_PENDING}/{@code REFUND_PENDING}
 * because the synchronous provider call was ambiguous (timed out, or the process crashed mid-call).
 *
 * <p>Webhook correlation (see {@code ProcessWebhookService}) resolves most of these within seconds,
 * so this exists for whatever it misses: a webhook never delivered, delivered while this app was
 * down, or misconfigured. Each candidate re-sends the same request the original attempt did, under
 * the same idempotency key, so a provider that did process it the first time simply returns its
 * cached response instead of acting twice.
 */
@Service
public class ReconcilePendingPaymentsService {

    private static final Logger log = LoggerFactory.getLogger(ReconcilePendingPaymentsService.class);

    /**
     * How long a payment must have gone untouched before this job will even look at it — long enough
     * that a reconciliation pass never races the synchronous call that placed it into a pending state
     * and may still be in flight.
     */
    private static final Duration MIN_PENDING_AGE = Duration.ofMinutes(1);

    private final PaymentRepositoryPort paymentRepository;
    private final PaymentGatewayResolver gatewayResolver;
    private final DomainEventPublisherPort eventPublisher;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ReconcilePendingPaymentsService(
            PaymentRepositoryPort paymentRepository,
            PaymentGatewayResolver gatewayResolver,
            DomainEventPublisherPort eventPublisher,
            ReconciliationProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.gatewayResolver = gatewayResolver;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** One pass over every authorization stuck pending past the grace period. */
    public void reconcileAuthorizations() {
        List<Payment> candidates =
                paymentRepository.findAuthorizationPendingOlderThan(clock.instant().minus(MIN_PENDING_AGE));
        for (Payment candidate : candidates) {
            reconcileOne(candidate.id(), this::reconcileAuthorization);
        }
    }

    /** One pass over every refund stuck pending past the grace period. */
    public void reconcileRefunds() {
        List<Payment> candidates =
                paymentRepository.findRefundPendingOlderThan(clock.instant().minus(MIN_PENDING_AGE));
        for (Payment candidate : candidates) {
            reconcileOne(candidate.id(), this::reconcileRefund);
        }
    }

    /**
     * Each candidate gets its own transaction, isolated from the rest of the batch: one payment
     * erroring unexpectedly must not abort every other candidate in the same pass.
     */
    private void reconcileOne(PaymentId paymentId, Consumer<Payment> action) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Re-locked and re-checked here, not trusted from the candidate scan: another
                // pass, or a webhook, may have already resolved it in the time since.
                paymentRepository.findByIdForUpdate(paymentId).ifPresent(action);
            });
        } catch (RuntimeException ex) {
            log.error("Reconciliation pass for payment {} failed unexpectedly", paymentId, ex);
        }
    }

    private void reconcileAuthorization(Payment payment) {
        if (payment.status() != PaymentStatus.AUTHORIZATION_PENDING) {
            return;
        }
        PaymentGatewayPort gateway = gatewayResolver.resolve(payment.provider());
        PaymentGatewayPort.GatewayAuthorization authorization;
        try {
            authorization = gateway.authorize(payment, payment.paymentMethodToken(), payment.captureMode());
        } catch (RuntimeException ex) {
            log.warn("Reconciliation authorize call for {} is still ambiguous", payment.id(), ex);
            recordAttemptOrExhaust(payment, this::exhaustAuthorization);
            return;
        }

        if (authorization.successful()) {
            payment.markAuthorized(authorization.reference(), clock.instant());
            if (authorization.captured()) {
                payment.capture(payment.authorizedAmount(), clock.instant());
            }
        } else {
            payment.markFailed(authorization.failureReason(), clock.instant());
        }
        save(payment);
    }

    private void reconcileRefund(Payment payment) {
        if (payment.status() != PaymentStatus.REFUND_PENDING) {
            return;
        }
        PaymentGatewayPort gateway = gatewayResolver.resolve(payment.provider());
        PaymentGatewayPort.GatewayOperation operation;
        try {
            operation = gateway.refund(
                    payment,
                    payment.pendingRefundAmount().orElseThrow(),
                    payment.pendingRefundReason().orElse(null));
        } catch (RuntimeException ex) {
            log.warn("Reconciliation refund call for {} is still ambiguous", payment.id(), ex);
            recordAttemptOrExhaust(payment, this::exhaustRefund);
            return;
        }

        if (operation.successful()) {
            payment.resolveRefundPending(clock.instant());
        } else {
            payment.cancelRefundPending(clock.instant());
        }
        save(payment);
    }

    /**
     * Bounded retry: an attempt count and a wall-clock age, either of which stops further automatic
     * retries — there is no Stripe-prescribed cadence for this, so both limits are ours to define.
     */
    private void recordAttemptOrExhaust(Payment payment, Consumer<Payment> exhaust) {
        boolean attemptsExhausted = payment.reconciliationAttempts() + 1 >= properties.maxAttempts();
        boolean ageExhausted = payment.pendingSince()
                .map(since -> !Duration.between(since, clock.instant()).minus(properties.maxAge()).isNegative())
                .orElse(false);
        if (attemptsExhausted || ageExhausted) {
            log.error(
                    "Payment {} exhausted reconciliation ({} attempts, pending since {}); giving up",
                    payment.id(),
                    payment.reconciliationAttempts() + 1,
                    payment.pendingSince().map(Instant::toString).orElse("unknown"));
            exhaust.accept(payment);
        } else {
            payment.recordReconciliationAttempt(clock.instant());
        }
        save(payment);
    }

    private void exhaustAuthorization(Payment payment) {
        payment.markFailed("reconciliation exhausted without a confirmed outcome", clock.instant());
    }

    private void exhaustRefund(Payment payment) {
        payment.cancelRefundPending(clock.instant());
    }

    private void save(Payment payment) {
        Payment saved = paymentRepository.save(payment);
        eventPublisher.publishAll(payment.pullDomainEvents());
        log.info("Reconciliation pass left payment {} as {}", saved.id(), saved.status());
    }
}
