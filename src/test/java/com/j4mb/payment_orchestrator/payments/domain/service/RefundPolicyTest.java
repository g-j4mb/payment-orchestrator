package com.j4mb.payment_orchestrator.payments.domain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
import com.j4mb.payment_orchestrator.payments.domain.model.Payment;
import com.j4mb.payment_orchestrator.payments.domain.vo.CaptureMode;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderReference;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefundPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private final RefundPolicy policy = new RefundPolicy();
    private Payment payment;

    @BeforeEach
    void capturedPayment() {
        payment = Payment.initiate(
                ProviderType.STRIPE,
                usd("100.00"),
                new IdempotencyKey("key-" + System.nanoTime()),
                "tok_visa",
                CaptureMode.MANUAL,
                NOW);
        payment.markAuthorized(new ProviderReference("pi_123"), NOW);
        payment.capture(usd("100.00"), NOW);
    }

    @Test
    void allowsARefundWithinTheRefundableBalance() {
        assertThatCode(() -> policy.validate(payment, usd("40.00"))).doesNotThrowAnyException();
    }

    @Test
    void allowsARefundOfTheFullRefundableBalance() {
        assertThatCode(() -> policy.validate(payment, usd("100.00"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAZeroAmount() {
        assertThatThrownBy(() -> policy.validate(payment, usd("0.00")))
                .isInstanceOf(InvalidRefundAmountException.class);
    }

    @Test
    void rejectsAnAmountAboveTheRefundableBalance() {
        assertThatThrownBy(() -> policy.validate(payment, usd("100.01")))
                .isInstanceOf(InvalidRefundAmountException.class);
    }

    @Test
    void rejectsAPaymentThatIsNotInARefundableStatus() {
        Payment authorizedOnly = Payment.initiate(
                ProviderType.STRIPE,
                usd("100.00"),
                new IdempotencyKey("key-" + System.nanoTime()),
                "tok_visa",
                CaptureMode.MANUAL,
                NOW);
        authorizedOnly.markAuthorized(new ProviderReference("pi_456"), NOW);

        assertThatThrownBy(() -> policy.validate(authorizedOnly, usd("10.00")))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);
    }
}
