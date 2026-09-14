package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.scheduled;

import com.j4mb.payment_orchestrator.payments.application.service.ReconcilePendingPaymentsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drives {@link ReconcilePendingPaymentsService} — the backstop for pending payments
 * that webhook correlation did not catch.
 *
 * <p>Runs infrequently on purpose: webhooks are expected to resolve the common case within seconds,
 * long before this job's own interval would ever elapse.
 */
@Component
public class PendingPaymentReconciliationJob {

    private final ReconcilePendingPaymentsService reconciliationService;

    public PendingPaymentReconciliationJob(ReconcilePendingPaymentsService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${payments.reconciliation.interval:PT10M}")
    public void reconcilePendingPayments() {
        reconciliationService.reconcileAuthorizations();
        reconciliationService.reconcileRefunds();
    }
}
