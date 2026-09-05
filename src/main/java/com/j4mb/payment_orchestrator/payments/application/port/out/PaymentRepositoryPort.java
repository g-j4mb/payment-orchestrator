package com.j4mb.payment_orchestrator.payments.application.port.out;

import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.util.Optional;

/**
 * Outbound port for persisting the {@link Payment} aggregate.
 *
 * <p>This is the DDD Repository for the payments context, expressed purely in domain terms — no
 * {@code Page}, no {@code Specification}, no JPA. It sits with the other outbound ports so every
 * dependency the application layer has on the outside world is declared in one place.
 */
public interface PaymentRepositoryPort {

    Payment save(Payment payment);

    Optional<Payment> findById(PaymentId paymentId);

    /**
     * Loads a payment for modification, serializing concurrent operations against it.
     *
     * <p>Use this whenever the caller will act on an external provider based on what it reads. With
     * {@link #findById}, two concurrent captures both see the full balance available, both call the
     * provider, and the money moves twice — the optimistic-lock check on save comes too late, because
     * the funds have already moved. This blocks the second caller until the first commits, so its
     * balance check runs against the truth.
     *
     * <p>The lock is held for the rest of the transaction. Callers must keep that window tight.
     */
    Optional<Payment> findByIdForUpdate(PaymentId paymentId);

    /** Correlates an incoming webhook back to the aggregate it concerns. */
    Optional<Payment> findByProviderReference(ProviderType provider, ProviderReference reference);
}