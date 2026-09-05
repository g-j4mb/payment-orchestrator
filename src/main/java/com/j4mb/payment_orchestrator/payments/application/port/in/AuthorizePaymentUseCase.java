package com.j4mb.payment_orchestrator.payments.application.port.in;

import com.j4mb.payment_orchestrator.payments.application.command.AuthorizePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;

/**
 * Authorize a payment at a provider.
 *
 * <p>With {@code CaptureMode.AUTOMATIC} this is a purchase (sale) — authorization and capture in one
 * step.
 */
public interface AuthorizePaymentUseCase {

    PaymentResult authorize(AuthorizePaymentCommand command);
}