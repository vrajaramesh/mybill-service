package com.example.mybill.wholesale.service;

import java.time.LocalDate;
import java.time.Month;

/**
 * Pure formatting rules for wholesale document numbers (no database access).
 * Format: prefix + sep + [financial year + sep] + zero-padded sequence, e.g. QT/2025-26/0007.
 */
public final class WholesaleDocumentNumbering {

    public static final String ALL_PERIODS = "ALL";

    private WholesaleDocumentNumbering() {}

    /** Indian financial year (April–March): 2026-03-31 -> "2025-26", 2026-04-01 -> "2026-27". */
    public static String financialYear(LocalDate date) {
        int start = date.getMonthValue() >= Month.APRIL.getValue() ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** Counter period: the financial year when numbers reset yearly, otherwise one shared period. */
    public static String period(boolean resetEachFinancialYear, LocalDate documentDate) {
        return resetEachFinancialYear ? financialYear(documentDate) : ALL_PERIODS;
    }

    public static String format(String prefix, String separator, boolean includeFinancialYear, int padding,
                                LocalDate documentDate, long sequence) {
        if (sequence < 1) throw new IllegalArgumentException("Sequence must be positive");
        String sep = separator == null ? "" : separator;
        String seq = String.format("%0" + Math.max(1, padding) + "d", sequence);
        return includeFinancialYear
            ? prefix + sep + financialYear(documentDate) + sep + seq
            : prefix + sep + seq;
    }
}
