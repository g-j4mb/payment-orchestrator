package com.j4mb.payment_orchestrator.payments.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void isCapturable_isTrueOnlyForAuthorizedAndPartiallyCaptured() {
        for (PaymentStatus status : PaymentStatus.values()) {
            boolean expected = status == PaymentStatus.AUTHORIZED || status == PaymentStatus.PARTIALLY_CAPTURED;
            assertThat(status.isCapturable()).as("isCapturable() for %s", status).isEqualTo(expected);
        }
    }

    @Test
    void isRefundable_isTrueOnlyForStatesHoldingCapturedFunds() {
        for (PaymentStatus status : PaymentStatus.values()) {
            boolean expected = status == PaymentStatus.CAPTURED
                    || status == PaymentStatus.PARTIALLY_CAPTURED
                    || status == PaymentStatus.PARTIALLY_REFUNDED;
            assertThat(status.isRefundable()).as("isRefundable() for %s", status).isEqualTo(expected);
        }
    }

    @Test
    void isVoidable_isTrueOnlyForAuthorized() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.isVoidable())
                    .as("isVoidable() for %s", status)
                    .isEqualTo(status == PaymentStatus.AUTHORIZED);
        }
    }

    @Test
    void isTerminal_isTrueOnlyForEndStates() {
        for (PaymentStatus status : PaymentStatus.values()) {
            boolean expected = status == PaymentStatus.REFUNDED
                    || status == PaymentStatus.VOIDED
                    || status == PaymentStatus.FAILED;
            assertThat(status.isTerminal()).as("isTerminal() for %s", status).isEqualTo(expected);
        }
    }
}
