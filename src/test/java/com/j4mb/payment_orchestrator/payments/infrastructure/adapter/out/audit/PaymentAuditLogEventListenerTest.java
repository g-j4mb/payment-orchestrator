package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j4mb.payment_orchestrator.common.DomainEvent;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentAuthorized;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentCaptured;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentFailed;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentRefunded;
import com.j4mb.payment_orchestrator.payments.domain.event.PaymentVoided;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit-tests {@link PaymentAuditLogEventListener#on} directly rather than through Spring's
 * transactional-event machinery: {@code @TransactionalEventListener(BEFORE_COMMIT)} only fires
 * inside a real committing transaction, which the listener's own mapping and serialization logic
 * does not depend on — a plain call is enough to exercise it.
 */
class PaymentAuditLogEventListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:05:00Z");
    private static final Clock CLOCK = Clock.fixed(RECORDED_AT, ZoneOffset.UTC);
    private static final PaymentId PAYMENT_ID = PaymentId.newId();

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private PaymentAuditLogJpaRepository repository;
    private ObjectMapper objectMapper;
    private PaymentAuditLogEventListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentAuditLogJpaRepository.class);
        objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"serialized\":true}");
        listener = new PaymentAuditLogEventListener(repository, objectMapper, CLOCK);
    }

    @Test
    void paymentAuthorized_isWrittenToTheAuditLog() {
        PaymentAuthorized event = new PaymentAuthorized(
                PAYMENT_ID, ProviderType.STRIPE, new ProviderReference("pi_123"), usd("100.00"), OCCURRED_AT);

        listener.on(event);

        verify(repository)
                .save(argThat(entry -> entry.getPaymentId().equals(PAYMENT_ID.value())
                        && entry.getEventType().equals("PaymentAuthorized")
                        && entry.getPayload().equals("{\"serialized\":true}")
                        && entry.getOccurredAt().equals(OCCURRED_AT)
                        && entry.getRecordedAt().equals(RECORDED_AT)));
    }

    @Test
    void paymentCaptured_isWrittenToTheAuditLog() {
        PaymentCaptured event =
                new PaymentCaptured(PAYMENT_ID, ProviderType.STRIPE, usd("40.00"), usd("40.00"), OCCURRED_AT);

        listener.on(event);

        verify(repository).save(argThat(entry -> entry.getEventType().equals("PaymentCaptured")));
    }

    @Test
    void paymentRefunded_isWrittenToTheAuditLog() {
        PaymentRefunded event =
                new PaymentRefunded(PAYMENT_ID, ProviderType.STRIPE, usd("10.00"), usd("10.00"), OCCURRED_AT);

        listener.on(event);

        verify(repository).save(argThat(entry -> entry.getEventType().equals("PaymentRefunded")));
    }

    @Test
    void paymentVoided_isWrittenToTheAuditLog() {
        PaymentVoided event = new PaymentVoided(PAYMENT_ID, ProviderType.STRIPE, OCCURRED_AT);

        listener.on(event);

        verify(repository).save(argThat(entry -> entry.getEventType().equals("PaymentVoided")));
    }

    @Test
    void paymentFailed_isWrittenToTheAuditLog() {
        PaymentFailed event = new PaymentFailed(PAYMENT_ID, ProviderType.STRIPE, "card_declined", OCCURRED_AT);

        listener.on(event);

        verify(repository).save(argThat(entry -> entry.getEventType().equals("PaymentFailed")));
    }

    @Test
    void unmappedEventType_writesNoAuditRow() {
        // Any DomainEvent other than the five payments.domain.event records falls through
        // paymentIdOf()'s default branch; DomainEvent's single abstract method makes a lambda enough.
        DomainEvent unmapped = () -> OCCURRED_AT;

        listener.on(unmapped);

        verify(repository, never()).save(any());
    }

    @Test
    void serializationFailure_stillWritesAPlaceholderPayloadRatherThanLosingTheAuditRow() {
        when(objectMapper.writeValueAsString(any())).thenThrow(mock(JacksonException.class));
        PaymentVoided event = new PaymentVoided(PAYMENT_ID, ProviderType.STRIPE, OCCURRED_AT);

        listener.on(event);

        verify(repository).save(argThat(entry -> entry.getPayload().contains("could not be serialized")));
    }
}
