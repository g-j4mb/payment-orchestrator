package com.j4mb.payment_orchestrator.payments.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    @Nested
    class Construction {

        @Test
        void rejectsNullAmount() {
            assertThatThrownBy(() -> new Money(null, Currency.getInstance("USD")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsNullCurrency() {
            assertThatThrownBy(() -> new Money(BigDecimal.TEN, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsNegativeAmount() {
            assertThatThrownBy(() -> usd("-5.00")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsMoreDecimalPlacesThanTheCurrencyAllows() {
            assertThatThrownBy(() -> usd("10.999")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsDecimalsForAZeroDecimalCurrency() {
            assertThatThrownBy(() -> Money.of(new BigDecimal("100.5"), "JPY"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsAWholeAmountForAZeroDecimalCurrency() {
            assertThat(Money.of(new BigDecimal("100"), "JPY")).hasToString("100 JPY");
        }

        @Test
        void padsAmountToTheCurrencysMinorUnits() {
            assertThat(usd("10000")).hasToString("10000.00 USD");
        }
    }

    @Nested
    class Factories {

        @Test
        void zero_isZeroInTheGivenCurrency() {
            Money zero = Money.zero(Currency.getInstance("USD"));

            assertThat(zero.isZero()).isTrue();
            assertThat(zero.currency()).isEqualTo(Currency.getInstance("USD"));
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void plus_addsAmountsInTheSameCurrency() {
            assertThat(usd("10.00").plus(usd("5.50"))).isEqualTo(usd("15.50"));
        }

        @Test
        void plus_rejectsDifferentCurrencies() {
            assertThatThrownBy(() -> usd("10.00").plus(Money.of(BigDecimal.TEN, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void minus_subtractsAmountsInTheSameCurrency() {
            assertThat(usd("10.00").minus(usd("3.00"))).isEqualTo(usd("7.00"));
        }

        @Test
        void minus_rejectsAResultBelowZero() {
            assertThatThrownBy(() -> usd("3.00").minus(usd("5.00"))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void minus_rejectsDifferentCurrencies() {
            assertThatThrownBy(() -> usd("10.00").minus(Money.of(BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Comparison {

        @Test
        void isZero_isTrueOnlyForZeroAmount() {
            assertThat(usd("0.00").isZero()).isTrue();
            assertThat(usd("0.01").isZero()).isFalse();
        }

        @Test
        void isGreaterThan_comparesAmountsInTheSameCurrency() {
            assertThat(usd("10.00").isGreaterThan(usd("5.00"))).isTrue();
            assertThat(usd("5.00").isGreaterThan(usd("10.00"))).isFalse();
            assertThat(usd("5.00").isGreaterThan(usd("5.00"))).isFalse();
        }

        @Test
        void isGreaterThan_rejectsDifferentCurrencies() {
            assertThatThrownBy(() -> usd("10.00").isGreaterThan(Money.of(BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void compareTo_ordersBySameCurrencyAmount() {
            assertThat(usd("10.00").compareTo(usd("5.00"))).isPositive();
            assertThat(usd("5.00").compareTo(usd("10.00"))).isNegative();
            assertThat(usd("5.00").compareTo(usd("5.00"))).isZero();
        }

        @Test
        void compareTo_rejectsDifferentCurrencies() {
            assertThatThrownBy(() -> usd("5.00").compareTo(Money.of(BigDecimal.ONE, "EUR")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
