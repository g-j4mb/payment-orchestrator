package com.j4mb.payment_orchestrator.payments.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentIdTest {

    @Test
    void newId_generatesADistinctIdEachTime() {
        assertThat(PaymentId.newId()).isNotEqualTo(PaymentId.newId());
    }

    @Test
    void of_parsesAValidUuidString() {
        UUID uuid = UUID.randomUUID();

        assertThat(PaymentId.of(uuid.toString())).isEqualTo(new PaymentId(uuid));
    }

    @Test
    void of_rejectsANonUuidString() {
        assertThatThrownBy(() -> PaymentId.of("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_returnsTheRawUuidValue() {
        UUID uuid = UUID.randomUUID();

        assertThat(new PaymentId(uuid)).hasToString(uuid.toString());
    }
}
