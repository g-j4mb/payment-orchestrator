package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.mapper;

import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.PaymentJpaEntity;
import java.util.Currency;
import org.springframework.stereotype.Component;

/**
 * Translates between the {@code Payment} aggregate and its persistence mirror.
 *
 * <p>Written by hand rather than generated: the aggregate has no setters, so rebuilding it goes
 * through {@link Payment#rehydrate} and cannot be inferred from field names alone.
 */
@Component
public class PaymentEntityMapper {

    /** Rebuilds the aggregate from a persisted row. */
    public Payment toDomain(PaymentJpaEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        return Payment.rehydrate(
                new PaymentId(entity.getId()),
                ProviderType.valueOf(entity.getProvider()),
                entity.getProviderReference() == null ? null : new ProviderReference(entity.getProviderReference()),
                new Money(entity.getAuthorizedAmount(), currency),
                new Money(entity.getCapturedAmount(), currency),
                new Money(entity.getRefundedAmount(), currency),
                PaymentStatus.valueOf(entity.getStatus()),
                new IdempotencyKey(entity.getIdempotencyKey()),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** Creates a row for a payment that has never been persisted. */
    public PaymentJpaEntity toNewEntity(Payment payment) {
        PaymentJpaEntity entity = new PaymentJpaEntity(
                payment.id().value(),
                payment.idempotencyKey().value(),
                payment.authorizedAmount().currency().getCurrencyCode(),
                payment.authorizedAmount().amount(),
                payment.createdAt());
        applyMutableState(payment, entity);
        return entity;
    }

    /**
     * Copies the aggregate's current state onto an already-managed row.
     *
     * <p>Updating in place rather than replacing the entity is what preserves the {@code @Version}
     * value, so optimistic locking keeps working across a save.
     */
    public void applyMutableState(Payment payment, PaymentJpaEntity entity) {
        entity.setProvider(payment.provider().name());
        entity.setProviderReference(
                payment.providerReference().map(ProviderReference::value).orElse(null));
        entity.setCapturedAmount(payment.capturedAmount().amount());
        entity.setRefundedAmount(payment.refundedAmount().amount());
        entity.setStatus(payment.status().name());
        entity.setFailureReason(payment.failureReason().orElse(null));
        entity.setUpdatedAt(payment.updatedAt());
    }
}
