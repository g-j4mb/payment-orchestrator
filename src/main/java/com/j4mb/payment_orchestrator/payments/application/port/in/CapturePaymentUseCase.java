package com.j4mb.payment_orchestrator.payments.application.port.in;

import com.j4mb.payment_orchestrator.payments.application.command.CapturePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;

/** Capture funds against an existing authorization, fully or in part. */
public interface CapturePaymentUseCase {

    PaymentResult capture(CapturePaymentCommand command);
}