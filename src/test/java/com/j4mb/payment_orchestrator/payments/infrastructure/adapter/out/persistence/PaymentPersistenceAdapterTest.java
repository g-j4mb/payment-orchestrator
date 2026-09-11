package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j4mb.payment_orchestrator.AbstractPostgresIntegrationTest;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.mapper.PaymentEntityMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Exercises {@link PaymentPersistenceAdapter} against a real Postgres container, not an in-memory
 * substitute — the schema uses Postgres-specific types and a partial unique index that an embedded
 * database could not validate.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentPersistenceAdapter.class, PaymentEntityMapper.class})
class PaymentPersistenceAdapterTest extends AbstractPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    @Autowired
    private PaymentPersistenceAdapter adapter;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void save_newPayment_persistsAllFieldsAndCanBeReloaded() {
        Payment payment = Payment.initiate(ProviderType.STRIPE, usd("100.00"), new IdempotencyKey("key-1"), NOW);
        payment.markAuthorized(new ProviderReference("pi_abc"), NOW);

        adapter.save(payment);
        entityManager.flush();
        entityManager.clear();

        Payment reloaded = adapter.findById(payment.id()).orElseThrow();
        assertThat(reloaded.id()).isEqualTo(payment.id());
        assertThat(reloaded.provider()).isEqualTo(ProviderType.STRIPE);
        assertThat(reloaded.providerReference()).contains(new ProviderReference("pi_abc"));
        assertThat(reloaded.authorizedAmount()).isEqualTo(usd("100.00"));
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(reloaded.idempotencyKey()).isEqualTo(payment.idempotencyKey());
        assertThat(reloaded.createdAt()).isEqualTo(NOW);
    }

    @Test
    void save_existingPayment_updatesInPlaceRatherThanInsertingASecondRow() {
        Payment payment = Payment.initiate(ProviderType.STRIPE, usd("100.00"), new IdempotencyKey("key-2"), NOW);
        payment.markAuthorized(new ProviderReference("pi_def"), NOW);
        adapter.save(payment);
        entityManager.flush();
        entityManager.clear();

        Payment reloaded = adapter.findById(payment.id()).orElseThrow();
        reloaded.capture(usd("100.00"), NOW);
        adapter.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        Long rowCount = entityManager
                .getEntityManager()
                .createQuery("select count(p) from PaymentJpaEntity p where p.id = :id", Long.class)
                .setParameter("id", payment.id().value())
                .getSingleResult();
        assertThat(rowCount).isEqualTo(1L);

        Payment afterCapture = adapter.findById(payment.id()).orElseThrow();
        assertThat(afterCapture.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(afterCapture.capturedAmount()).isEqualTo(usd("100.00"));
    }

    @Test
    void findById_returnsEmptyForAnUnknownId() {
        assertThat(adapter.findById(PaymentId.newId())).isEmpty();
    }

    @Test
    void findByProviderReference_correlatesByProviderAndReference() {
        Payment payment = Payment.initiate(ProviderType.ADYEN, usd("50.00"), new IdempotencyKey("key-3"), NOW);
        payment.markAuthorized(new ProviderReference("psp_123"), NOW);
        adapter.save(payment);
        entityManager.flush();
        entityManager.clear();

        assertThat(adapter.findByProviderReference(ProviderType.ADYEN, new ProviderReference("psp_123")))
                .map(Payment::id)
                .contains(payment.id());
        assertThat(adapter.findByProviderReference(ProviderType.STRIPE, new ProviderReference("psp_123")))
                .isEmpty();
        assertThat(adapter.findByProviderReference(ProviderType.ADYEN, new ProviderReference("psp_999")))
                .isEmpty();
    }

    @Test
    void findByIdForUpdate_returnsTheSamePaymentAsFindById() {
        Payment payment = Payment.initiate(ProviderType.STRIPE, usd("100.00"), new IdempotencyKey("key-6"), NOW);
        adapter.save(payment);
        entityManager.flush();
        entityManager.clear();

        assertThat(adapter.findByIdForUpdate(payment.id())).map(Payment::id).contains(payment.id());
    }

    @Test
    void duplicateProviderReferenceForTheSameProvider_violatesTheUniqueConstraint() {
        ProviderReference sharedReference = new ProviderReference("pi_shared");
        Payment first = Payment.initiate(ProviderType.STRIPE, usd("10.00"), new IdempotencyKey("key-7"), NOW);
        first.markAuthorized(sharedReference, NOW);
        adapter.save(first);
        entityManager.flush();

        Payment second = Payment.initiate(ProviderType.STRIPE, usd("20.00"), new IdempotencyKey("key-8"), NOW);
        second.markAuthorized(sharedReference, NOW);

        // Flushed through the repository proxy, not the raw EntityManager, so Spring's exception
        // translation actually applies — the same path a real @Transactional service commit takes.
        assertThatThrownBy(() -> {
                    adapter.save(second);
                    paymentJpaRepository.flush();
                })
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
