package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j4mb.payment_orchestrator.AbstractPostgresIntegrationTest;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.mapper.PaymentEntityMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    private static Payment newPayment(ProviderType provider, Money amount, String key) {
        return Payment.initiate(provider, amount, new IdempotencyKey(key), "tok_visa", CaptureMode.MANUAL, NOW);
    }

    @Autowired
    private PaymentPersistenceAdapter adapter;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void save_newPayment_persistsAllFieldsAndCanBeReloaded() {
        Payment payment = newPayment(ProviderType.STRIPE, usd("100.00"), "key-1");
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
        assertThat(reloaded.paymentMethodToken()).isEqualTo("tok_visa");
        assertThat(reloaded.captureMode()).isEqualTo(CaptureMode.MANUAL);
        assertThat(reloaded.createdAt()).isEqualTo(NOW);
    }

    @Test
    void save_existingPayment_updatesInPlaceRatherThanInsertingASecondRow() {
        Payment payment = newPayment(ProviderType.STRIPE, usd("100.00"), "key-2");
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
    void save_paymentWithAPendingRefundAttempt_persistsAndReloadsTheReconciliationFields() {
        Payment payment = newPayment(ProviderType.STRIPE, usd("100.00"), "key-pending-refund");
        payment.markAuthorized(new ProviderReference("pi_refund"), NOW);
        payment.capture(usd("100.00"), NOW);
        payment.beginRefundAttempt(usd("40.00"), "requested_by_customer", "refund-attempt-1", NOW);
        adapter.save(payment);
        entityManager.flush();
        entityManager.clear();

        Payment reloaded = adapter.findById(payment.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(reloaded.pendingRefundAmount()).contains(usd("40.00"));
        assertThat(reloaded.pendingRefundReason()).contains("requested_by_customer");
        assertThat(reloaded.refundAttemptIdempotencyKey()).contains("refund-attempt-1");
        assertThat(reloaded.pendingSince()).contains(NOW);
        assertThat(reloaded.reconciliationAttempts()).isZero();
    }

    @Test
    void save_paymentAfterAReconciliationAttempt_persistsTheIncrementedCounter() {
        Payment payment = newPayment(ProviderType.STRIPE, usd("100.00"), "key-reconciled");
        payment.markAuthorizationPending(NOW);
        payment.recordReconciliationAttempt(NOW.plusSeconds(60));
        adapter.save(payment);
        entityManager.flush();
        entityManager.clear();

        Payment reloaded = adapter.findById(payment.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.AUTHORIZATION_PENDING);
        assertThat(reloaded.reconciliationAttempts()).isEqualTo(1);
        assertThat(reloaded.pendingSince()).contains(NOW);
    }

    @Test
    void findById_returnsEmptyForAnUnknownId() {
        assertThat(adapter.findById(PaymentId.newId())).isEmpty();
    }

    @Test
    void findByProviderReference_correlatesByProviderAndReference() {
        Payment payment = newPayment(ProviderType.ADYEN, usd("50.00"), "key-3");
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
        Payment payment = newPayment(ProviderType.STRIPE, usd("100.00"), "key-6");
        adapter.save(payment);
        entityManager.flush();
        entityManager.clear();

        assertThat(adapter.findByIdForUpdate(payment.id())).map(Payment::id).contains(payment.id());
    }

    @Test
    void findAuthorizationPendingOlderThan_returnsOnlyPendingRowsPastTheThreshold() {
        Payment old = newPayment(ProviderType.STRIPE, usd("10.00"), "key-old-pending");
        old.markAuthorizationPending(NOW);
        adapter.save(old);

        Payment recent = newPayment(ProviderType.STRIPE, usd("10.00"), "key-recent-pending");
        recent.markAuthorizationPending(NOW.plusSeconds(3600));
        adapter.save(recent);

        Payment authorized = newPayment(ProviderType.STRIPE, usd("10.00"), "key-authorized");
        authorized.markAuthorized(new ProviderReference("pi_not_pending"), NOW);
        adapter.save(authorized);

        entityManager.flush();
        entityManager.clear();

        List<Payment> candidates = adapter.findAuthorizationPendingOlderThan(NOW.plusSeconds(60));

        assertThat(candidates).extracting(Payment::id).containsExactly(old.id());
    }

    @Test
    void findRefundPendingOlderThan_returnsOnlyPendingRowsPastTheThreshold() {
        Payment old = newPayment(ProviderType.STRIPE, usd("100.00"), "key-old-refund");
        old.markAuthorized(new ProviderReference("pi_old_refund"), NOW);
        old.capture(usd("100.00"), NOW);
        old.beginRefundAttempt(usd("20.00"), "reason", "attempt-old", NOW);
        adapter.save(old);

        Payment recent = newPayment(ProviderType.STRIPE, usd("100.00"), "key-recent-refund");
        recent.markAuthorized(new ProviderReference("pi_recent_refund"), NOW);
        recent.capture(usd("100.00"), NOW);
        recent.beginRefundAttempt(usd("20.00"), "reason", "attempt-recent", NOW.plusSeconds(3600));
        adapter.save(recent);

        Payment captured = newPayment(ProviderType.STRIPE, usd("100.00"), "key-captured");
        captured.markAuthorized(new ProviderReference("pi_captured"), NOW);
        captured.capture(usd("100.00"), NOW);
        adapter.save(captured);

        entityManager.flush();
        entityManager.clear();

        List<Payment> candidates = adapter.findRefundPendingOlderThan(NOW.plusSeconds(60));

        assertThat(candidates).extracting(Payment::id).containsExactly(old.id());
    }

    @Test
    void duplicateProviderReferenceForTheSameProvider_violatesTheUniqueConstraint() {
        ProviderReference sharedReference = new ProviderReference("pi_shared");
        Payment first = newPayment(ProviderType.STRIPE, usd("10.00"), "key-7");
        first.markAuthorized(sharedReference, NOW);
        adapter.save(first);
        entityManager.flush();

        Payment second = newPayment(ProviderType.STRIPE, usd("20.00"), "key-8");
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
