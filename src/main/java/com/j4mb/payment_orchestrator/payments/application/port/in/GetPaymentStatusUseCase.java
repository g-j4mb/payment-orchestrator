package com.j4mb.payment_orchestrator.payments.application.port.in;

import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;

/** Read the current state of a payment. */
public interface GetPaymentStatusUseCase {

    PaymentResult getStatus(PaymentId paymentId);
}