package com.j4mb.payment_orchestrator.payments.application.service;

import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.in.GetPaymentStatusUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a payment's current state from local storage. */
@Service
public class GetPaymentStatusService implements GetPaymentStatusUseCase {

    private final PaymentRepositoryPort paymentRepository;

    public GetPaymentStatusService(PaymentRepositoryPort paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResult getStatus(PaymentId paymentId) {
        return paymentRepository
                .findById(paymentId)
                .map(PaymentResult::from)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}