package com.j4mb.payment_orchestrator.payments.domain.event;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.time.Instant;

/** The authorization was released before any capture. */
public record PaymentVoided(PaymentId paymentId, ProviderType provider, Instant occurredAt)
        implements DomainEvent {}