package com.j4mb.payment_orchestrator.payments.application.port.in;

import com.j4mb.payment_orchestrator.payments.application.command.RefundPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;

/** Return captured funds to the payer, fully or in part. */
public interface RefundPaymentUseCase {

    PaymentResult refund(RefundPaymentCommand command);
}