package com.j4mb.payment_orchestrator.payments.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.out.PaymentRepositoryPort;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetPaymentStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private PaymentRepositoryPort paymentRepository;
    private GetPaymentStatusService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepositoryPort.class);
        service = new GetPaymentStatusService(paymentRepository);
    }

    @Test
    void existingPayment_returnsItsCurrentState() {
        PaymentId id = PaymentId.newId();
        Payment payment = Payment.rehydrate(
                id,
                ProviderType.STRIPE,
                new ProviderReference("pi_123"),
                Money.of(new BigDecimal("100.00"), "USD"),
                Money.of(new BigDecimal("100.00"), "USD"),
                Money.of(new BigDecimal("0.00"), "USD"),
                PaymentStatus.CAPTURED,
                new IdempotencyKey("key-1"),
                null,
                NOW,
                NOW);
        when(paymentRepository.findById(id)).thenReturn(Optional.of(payment));

        PaymentResult result = service.getStatus(id);

        assertThat(result.paymentId()).isEqualTo(id.value());
        assertThat(result.status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void missingPayment_throwsNotFound() {
        PaymentId id = PaymentId.newId();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(id)).isInstanceOf(PaymentNotFoundException.class);
    }
}
