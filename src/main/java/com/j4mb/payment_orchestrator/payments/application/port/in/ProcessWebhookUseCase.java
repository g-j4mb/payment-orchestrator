package com.j4mb.payment_orchestrator.payments.application.port.in;

import com.j4mb.payment_orchestrator.payments.application.command.ProcessWebhookCommand;

/**
 * Apply an asynchronous provider notification to the matching payment.
 *
 * <p>Providers redeliver webhooks, so handling must be idempotent: applying the same notification
 * twice must not change the payment twice.
 */
public interface ProcessWebhookUseCase {

    void process(ProcessWebhookCommand command);
}