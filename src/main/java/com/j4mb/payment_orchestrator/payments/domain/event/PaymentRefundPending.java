package com.j4mb.payment_orchestrator.payments.domain.event;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.time.Instant;

/** A refund call was sent to the provider; its outcome is not yet known. */
public record PaymentRefundPending(PaymentId paymentId, ProviderType provider, Money amount, Instant occurredAt)
        implements DomainEvent {}
