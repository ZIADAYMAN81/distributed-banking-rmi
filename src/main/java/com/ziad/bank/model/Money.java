package com.ziad.bank.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is stored as whole cents in a {@code long}, so arithmetic is exact (no floating-point drift). */
public final class Money {
    private Money() {
    }

    public static long parse(String amount) throws BankException {
        try {
            BigDecimal value = new BigDecimal(amount.trim());
            if (value.scale() > 2) {
                throw new BankException("Amounts can have at most two decimal places: " + amount);
            }
            return value.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new BankException("Not a valid amount: " + amount);
        }
    }

    public static String format(long cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
