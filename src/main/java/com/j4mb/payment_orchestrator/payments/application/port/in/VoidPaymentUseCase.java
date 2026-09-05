package com.j4mb.payment_orchestrator.payments.application.port.in;

import com.j4mb.payment_orchestrator.payments.application.command.VoidPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;

/** Release an authorization before any funds are captured. */
public interface VoidPaymentUseCase {

    PaymentResult voidPayment(VoidPaymentCommand command);
}