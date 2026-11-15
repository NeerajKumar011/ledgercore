package com.ledgercore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    @DisplayName("Should successfully add two Money objects with the same currency")
    void shouldAddMoneyWithSameCurrency() {
        Money m1 = Money.of(1000L, USD);
        Money m2 = Money.of(550L, USD);

        Money result = m1.plus(m2);

        assertThat(result.amountMinor()).isEqualTo(1550L);
        assertThat(result.currency()).isEqualTo(USD);
    }

    @Test
    @DisplayName("Should successfully subtract two Money objects with the same currency")
    void shouldSubtractMoneyWithSameCurrency() {
        Money m1 = Money.of(1000L, USD);
        Money m2 = Money.of(325L, USD);

        Money result = m1.minus(m2);

        assertThat(result.amountMinor()).isEqualTo(675L);
        assertThat(result.currency()).isEqualTo(USD);
    }

    @Test
    @DisplayName("plus() should throw ArithmeticException on numeric overflow")
    void shouldThrowArithmeticExceptionOnAdditionOverflow() {
        Money max = Money.of(Long.MAX_VALUE, USD);
        Money one = Money.of(1L, USD);

        assertThatThrownBy(() -> max.plus(one))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("minus() should throw ArithmeticException on numeric underflow")
    void shouldThrowArithmeticExceptionOnSubtractionUnderflow() {
        Money min = Money.of(Long.MIN_VALUE, USD);
        Money one = Money.of(1L, USD);

        assertThatThrownBy(() -> min.minus(one))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("plus() should throw IllegalArgumentException when currencies differ")
    void shouldThrowIllegalArgumentExceptionOnCurrencyMismatchInAddition() {
        Money usd = Money.of(100L, USD);
        Money eur = Money.of(100L, EUR);

        assertThatThrownBy(() -> usd.plus(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("minus() should throw IllegalArgumentException when currencies differ")
    void shouldThrowIllegalArgumentExceptionOnCurrencyMismatchInSubtraction() {
        Money usd = Money.of(100L, USD);
        Money eur = Money.of(100L, EUR);

        assertThatThrownBy(() -> usd.minus(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("Constructor should reject null Currency")
    void shouldRejectNullCurrency() {
        assertThatThrownBy(() -> new Money(100L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currency must not be null");
    }

    @Test
    @DisplayName("plus() and minus() should reject null operand")
    void shouldRejectNullOperands() {
        Money money = Money.of(100L, USD);

        assertThatThrownBy(() -> money.plus(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> money.minus(null))
                .isInstanceOf(NullPointerException.class);
    }
}
