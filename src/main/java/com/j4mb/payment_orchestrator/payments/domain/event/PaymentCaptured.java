package com.j4mb.payment_orchestrator.payments.domain.event;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.time.Instant;

/** Funds were taken from the payer, fully or in part. */
public record PaymentCaptured(
        PaymentId paymentId,
        ProviderType provider,
        Money capturedAmount,
        Money totalCapturedAmount,
        Instant occurredAt)
        implements DomainEvent {}