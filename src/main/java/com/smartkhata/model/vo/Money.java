package com.smartkhata.model.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary representation stored as 64-bit integer Paisha (1 BDT = 100 Paisha).
 * Strictly prevents floating-point rounding discrepancies across all accounting operations.
 */
public record Money(long paisha) implements Comparable<Money>, Serializable {

    public static final Money ZERO = new Money(0L);

    public static Money ofPaisha(long paisha) {
        return new Money(paisha);
    }

    public static Money ofBdt(BigDecimal bdt) {
        Objects.requireNonNull(bdt, "BDT amount cannot be null");
        long paisha = bdt.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        return new Money(paisha);
    }

    public static Money ofBdt(long bdt) {
        return new Money(Math.multiplyExact(bdt, 100L));
    }

    public static Money ofBdt(double bdt) {
        return ofBdt(BigDecimal.valueOf(bdt));
    }

    public BigDecimal toBdt() {
        return BigDecimal.valueOf(paisha, 2);
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "Cannot add null Money");
        return new Money(Math.addExact(this.paisha, other.paisha));
    }

    public Money subtract(Money other) {
        Objects.requireNonNull(other, "Cannot subtract null Money");
        return new Money(Math.subtractExact(this.paisha, other.paisha));
    }

    public Money multiply(long factor) {
        return new Money(Math.multiplyExact(this.paisha, factor));
    }

    public Money negate() {
        return new Money(Math.negateExact(this.paisha));
    }

    public boolean isPositive() {
        return paisha > 0L;
    }

    public boolean isNegative() {
        return paisha < 0L;
    }

    public boolean isZero() {
        return paisha == 0L;
    }

    @Override
    public int compareTo(Money o) {
        return Long.compare(this.paisha, o.paisha);
    }

    @Override
    public String toString() {
        return "BDT " + toBdt().toPlainString();
    }
}

