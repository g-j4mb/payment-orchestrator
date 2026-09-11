package com.j4mb.payment_orchestrator.payments.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j4mb.payment_orchestrator.payments.application.exception.IdempotencyConflictException;
import com.j4mb.payment_orchestrator.payments.application.port.out.IdempotencyStorePort;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IdempotencyCheckServiceTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("key-1");
    private static final String OPERATION = "authorize";

    private IdempotencyStorePort store;
    private IdempotencyCheckService service;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStorePort.class);
        service = new IdempotencyCheckService(store);
    }

    @Nested
    class Claim {

        @Test
        void freshKey_reservesItAndReturnsEmptySoTheCallerMayProceed() {
            when(store.findResult(KEY)).thenReturn(Optional.empty());
            when(store.reserve(KEY, OPERATION)).thenReturn(true);

            assertThat(service.claim(KEY, OPERATION)).isEmpty();
        }

        @Test
        void alreadyCompletedKey_returnsTheOriginalResultWithoutReserving() {
            PaymentId original = PaymentId.newId();
            when(store.findResult(KEY)).thenReturn(Optional.of(original));

            assertThat(service.claim(KEY, OPERATION)).contains(original);
            verify(store, never()).reserve(any(IdempotencyKey.class), anyString());
        }

        @Test
        void keyHeldByAnInFlightRequest_throwsConflict() {
            when(store.findResult(KEY)).thenReturn(Optional.empty());
            when(store.reserve(KEY, OPERATION)).thenReturn(false);

            assertThatThrownBy(() -> service.claim(KEY, OPERATION))
                    .isInstanceOf(IdempotencyConflictException.class);
        }

        @Test
        void keyCompletesBetweenTheFailedReserveAndTheFollowUpLookup_returnsItsResult() {
            PaymentId original = PaymentId.newId();
            when(store.findResult(KEY)).thenReturn(Optional.empty(), Optional.of(original));
            when(store.reserve(KEY, OPERATION)).thenReturn(false);

            assertThat(service.claim(KEY, OPERATION)).contains(original);
        }
    }

    @Nested
    class Complete {

        @Test
        void recordsTheResultOnTheStore() {
            PaymentId paymentId = PaymentId.newId();

            service.complete(KEY, paymentId);

            verify(store).recordResult(KEY, paymentId);
        }
    }

    @Nested
    class Abandon {

        @Test
        void releasesTheKeyOnTheStore() {
            service.abandon(KEY);

            verify(store).release(KEY);
        }
    }
}
