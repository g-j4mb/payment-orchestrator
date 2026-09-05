package com.j4mb.payment_orchestrator.payments.domain.event;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.time.Instant;

/** Funds were reserved at the provider. */
public record PaymentAuthorized(
        PaymentId paymentId,
        ProviderType provider,
        ProviderReference providerReference,
        Money authorizedAmount,
        Instant occurredAt)
        implements DomainEvent {}