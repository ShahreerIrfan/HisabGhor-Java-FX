package com.smartkhata.model.vo;

import com.smartkhata.util.BengaliMoneyFormatter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void shouldStoreExactIntegerPaisha() {
        Money m1 = Money.ofBdt(100);
        assertThat(m1.paisha()).isEqualTo(10000L);
        assertThat(m1.toBdt()).isEqualByComparingTo(new BigDecimal("100.00"));

        Money m2 = Money.ofBdt(new BigDecimal("486530.50"));
        assertThat(m2.paisha()).isEqualTo(48653050L);
    }

    @Test
    void shouldPerformExactArithmeticWithoutFloatingPointDrift() {
        Money a = Money.ofBdt(new BigDecimal("1000.00"));
        Money payment = Money.ofBdt(new BigDecimal("400.00"));

        Money due = a.subtract(payment);
        assertThat(due.paisha()).isEqualTo(60000L);
        assertThat(due.toBdt()).isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    void shouldFormatToSouthAsianGroupingCorrectly() {
        Money m1 = Money.ofBdt(new BigDecimal("486530.00"));
        assertThat(BengaliMoneyFormatter.format(m1)).isEqualTo("BDT 4,86,530");

        Money m2 = Money.ofBdt(new BigDecimal("192750.50"));
        assertThat(BengaliMoneyFormatter.format(m2)).isEqualTo("BDT 1,92,750.50");

        Money m3 = Money.ofBdt(new BigDecimal("327600"));
        assertThat(BengaliMoneyFormatter.format(m3)).isEqualTo("BDT 3,27,600");
    }

    @Test
    void shouldHandleNegativeAndZeroValues() {
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.ZERO.isPositive()).isFalse();

        Money neg = Money.ofBdt(-50);
        assertThat(neg.isNegative()).isTrue();
        assertThat(neg.paisha()).isEqualTo(-5000L);
        assertThat(BengaliMoneyFormatter.format(neg)).isEqualTo("-BDT 50");
    }
}

