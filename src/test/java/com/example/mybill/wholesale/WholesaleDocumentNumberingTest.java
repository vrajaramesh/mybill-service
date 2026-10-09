package com.example.mybill.wholesale;

import com.example.mybill.wholesale.service.WholesaleDocumentNumbering;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WholesaleDocumentNumberingTest {

    @Test
    void financialYear_switchesOnFirstApril() {
        assertThat(WholesaleDocumentNumbering.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(WholesaleDocumentNumbering.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(WholesaleDocumentNumbering.financialYear(LocalDate.of(2099, 12, 31))).isEqualTo("2099-00");
    }

    @Test
    void format_withFinancialYearAndPadding() {
        assertThat(WholesaleDocumentNumbering.format("QT", "/", true, 4, LocalDate.of(2026, 10, 5), 7))
            .isEqualTo("QT/2026-27/0007");
    }

    @Test
    void format_withoutFinancialYear_andOtherSeparators() {
        assertThat(WholesaleDocumentNumbering.format("SRISA-Q", "-", false, 5, LocalDate.of(2026, 10, 5), 42))
            .isEqualTo("SRISA-Q-00042");
        assertThat(WholesaleDocumentNumbering.format("Q", "", false, 3, LocalDate.of(2026, 10, 5), 1))
            .isEqualTo("Q001");
    }

    @Test
    void format_sequenceLongerThanPaddingIsNotTruncated() {
        assertThat(WholesaleDocumentNumbering.format("QT", "/", false, 2, LocalDate.of(2026, 1, 1), 12345))
            .isEqualTo("QT/12345");
    }

    @Test
    void period_isFinancialYearOnlyWhenResettingYearly() {
        LocalDate date = LocalDate.of(2026, 5, 1);
        assertThat(WholesaleDocumentNumbering.period(true, date)).isEqualTo("2026-27");
        assertThat(WholesaleDocumentNumbering.period(false, date)).isEqualTo(WholesaleDocumentNumbering.ALL_PERIODS);
    }

    @Test
    void format_rejectsNonPositiveSequence() {
        assertThatThrownBy(() -> WholesaleDocumentNumbering.format("QT", "/", true, 4, LocalDate.now(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
