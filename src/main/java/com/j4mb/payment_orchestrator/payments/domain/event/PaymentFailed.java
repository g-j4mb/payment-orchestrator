package com.j4mb.payment_orchestrator.payments.domain.event;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.time.Instant;

/**
 * The provider rejected the payment.
 *
 * <p>A decline is as much a fact worth auditing as a success — often more so, since declines are what
 * get questioned afterwards.
 */
public record PaymentFailed(PaymentId paymentId, ProviderType provider, String reason, Instant occurredAt)
        implements DomainEvent {}
