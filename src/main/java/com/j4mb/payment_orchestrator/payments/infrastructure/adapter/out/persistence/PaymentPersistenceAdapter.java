package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence;

import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.mapper.PaymentEntityMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Outbound adapter implementing {@link PaymentRepositoryPort} over Spring Data JPA. */
@Component
public class PaymentPersistenceAdapter implements PaymentRepositoryPort {

    private final PaymentJpaRepository repository;
    private final PaymentEntityMapper mapper;

    public PaymentPersistenceAdapter(PaymentJpaRepository repository, PaymentEntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity entity = repository
                .findById(payment.id().value())
                .map(existing -> {
                    // Mutate the managed row so its @Version survives — see PaymentEntityMapper.
                    mapper.applyMutableState(payment, existing);
                    return existing;
                })
                .orElseGet(() -> mapper.toNewEntity(payment));
        return mapper.toDomain(repository.save(entity));
    }

    @Override
    public Optional<Payment> findById(PaymentId paymentId) {
        return repository.findById(paymentId.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(PaymentId paymentId) {
        return repository.findByIdForUpdate(paymentId.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<Payment> findByProviderReference(ProviderType provider, ProviderReference reference) {
        return repository
                .findByProviderAndProviderReference(provider.name(), reference.value())
                .map(mapper::toDomain);
    }
}
