package com.smartkhata.util;

import com.smartkhata.model.vo.Money;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Formats monetary amounts according to South Asian grouping (Lakh, Crore)
 * e.g., BDT 4,86,530.00 matching docs/dashboard-reference.png.
 */
public final class BengaliMoneyFormatter {

    private BengaliMoneyFormatter() {}

    /**
     * Formats Money into South Asian currency format: BDT 4,86,530
     */
    public static String format(Money money) {
        if (money == null) {
            return "BDT 0";
        }

        long totalPaisha = money.paisha();
        boolean negative = totalPaisha < 0;
        long absPaisha = Math.abs(totalPaisha);

        long bdt = absPaisha / 100;
        long fraction = absPaisha % 100;

        String formattedBdt = formatSouthAsianInteger(bdt);
        String result = (fraction == 0)
                ? "BDT " + formattedBdt
                : String.format(Locale.US, "BDT %s.%02d", formattedBdt, fraction);

        return negative ? "-" + result : result;
    }

    /**
     * Formats integer part into South Asian comma format (e.g. 486530 -> 4,86,530).
     */
    public static String formatSouthAsianInteger(long number) {
        String numStr = String.valueOf(number);
        int len = numStr.length();
        if (len <= 3) {
            return numStr;
        }

        // Last 3 digits
        String lastThree = numStr.substring(len - 3);
        String remaining = numStr.substring(0, len - 3);

        StringBuilder sb = new StringBuilder();
        while (remaining.length() > 2) {
            int remLen = remaining.length();
            sb.insert(0, "," + remaining.substring(remLen - 2));
            remaining = remaining.substring(0, remLen - 2);
        }
        sb.insert(0, remaining);
        sb.append(",").append(lastThree);

        return sb.toString();
    }
}

