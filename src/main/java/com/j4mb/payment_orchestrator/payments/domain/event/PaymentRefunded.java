package com.j4mb.payment_orchestrator.payments.domain.event;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.time.Instant;

/** Captured funds were returned to the payer, fully or in part. */
public record PaymentRefunded(
        PaymentId paymentId,
        ProviderType provider,
        Money refundedAmount,
        Money totalRefundedAmount,
        Instant occurredAt)
        implements DomainEvent {}