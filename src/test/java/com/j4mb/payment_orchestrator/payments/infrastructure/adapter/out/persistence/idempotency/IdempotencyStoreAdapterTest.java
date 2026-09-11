package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.j4mb.payment_orchestrator.AbstractPostgresIntegrationTest;
import com.j4mb.payment_orchestrator.config.ClockConfig;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Exercises {@link IdempotencyStoreAdapter} against a real Postgres container.
 *
 * <p>{@code reserve}, {@code recordResult}, and {@code release} all run in their own {@code
 * REQUIRES_NEW} transaction (by design — see the class javadoc), which means they commit
 * independently of {@code @DataJpaTest}'s usual per-test rollback. Rows are removed explicitly in
 * {@link #cleanUp()} rather than relying on that rollback — the container is disposable, but keeping
 * each test's data isolated from the others still matters within a single run.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IdempotencyStoreAdapter.class, ClockConfig.class})
class IdempotencyStoreAdapterTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IdempotencyStoreAdapter adapter;

    @Autowired
    private IdempotencyRecordJpaRepository repository;

    private final List<IdempotencyKey> usedKeys = new ArrayList<>();

    private IdempotencyKey newKey() {
        IdempotencyKey key = new IdempotencyKey("test-" + UUID.randomUUID());
        usedKeys.add(key);
        return key;
    }

    @AfterEach
    void cleanUp() {
        usedKeys.forEach(key -> repository.deleteById(key.value()));
    }

    @Test
    void reserve_freshKey_claimsItAndReturnsTrue() {
        IdempotencyKey key = newKey();

        assertThat(adapter.reserve(key, "authorize")).isTrue();
        assertThat(repository.existsById(key.value())).isTrue();
    }

    @Test
    void reserve_alreadyClaimedKey_returnsFalseWithoutError() {
        IdempotencyKey key = newKey();
        adapter.reserve(key, "authorize");

        assertThat(adapter.reserve(key, "authorize")).isFalse();
    }

    @Test
    void reserve_concurrentClaimsForTheSameKey_onlyOneSucceeds() throws Exception {
        IdempotencyKey key = newKey();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> attempt = () -> {
            barrier.await();
            return adapter.reserve(key, "authorize");
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);

            boolean firstResult = first.get(5, TimeUnit.SECONDS);
            boolean secondResult = second.get(5, TimeUnit.SECONDS);

            // Exactly one racer claims the key; the other must see the unique-constraint violation on
            // its own insert and report false, without that exception escaping to the caller.
            assertThat(List.of(firstResult, secondResult)).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recordResult_thenFindResult_returnsTheStoredPaymentId() {
        IdempotencyKey key = newKey();
        adapter.reserve(key, "authorize");
        PaymentId paymentId = PaymentId.newId();

        adapter.recordResult(key, paymentId);

        assertThat(adapter.findResult(key)).contains(paymentId);
    }

    @Test
    void findResult_forAKeyNeverReserved_isEmpty() {
        IdempotencyKey key = newKey();

        assertThat(adapter.findResult(key)).isEmpty();
    }

    @Test
    void findResult_forAReservedButNotYetCompletedKey_isEmpty() {
        IdempotencyKey key = newKey();
        adapter.reserve(key, "authorize");

        assertThat(adapter.findResult(key)).isEmpty();
    }

    @Test
    void recordResult_forAKeyThatWasNeverReserved_isANoOp() {
        IdempotencyKey key = newKey();

        adapter.recordResult(key, PaymentId.newId());

        assertThat(repository.existsById(key.value())).isFalse();
    }

    @Test
    void release_removesTheReservation_allowingItToBeClaimedAgain() {
        IdempotencyKey key = newKey();
        adapter.reserve(key, "authorize");

        adapter.release(key);

        assertThat(repository.existsById(key.value())).isFalse();
        assertThat(adapter.reserve(key, "authorize")).isTrue();
    }

    @Test
    void release_forAKeyThatWasNeverReserved_isANoOp() {
        IdempotencyKey key = newKey();

        adapter.release(key);

        assertThat(repository.existsById(key.value())).isFalse();
    }
}
