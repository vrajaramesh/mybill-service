package com.example.mybill.wholesale;

import com.example.mybill.wholesale.service.WholesaleTaxCalculator;
import com.example.mybill.wholesale.service.WholesaleTaxCalculator.LineTax;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WholesaleTaxCalculatorTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void intraState_splitsGstIntoEqualCgstAndSgst() {
        LineTax t = WholesaleTaxCalculator.compute(d("100"), d("50"), d("5"), false);
        assertThat(t.taxable()).isEqualByComparingTo("5000.00");
        assertThat(t.cgst()).isEqualByComparingTo("125.00");
        assertThat(t.sgst()).isEqualByComparingTo("125.00");
        assertThat(t.igst()).isEqualByComparingTo("0");
        assertThat(t.total()).isEqualByComparingTo("5250.00");
    }

    @Test
    void intraState_oddPaisaGoesToSgst_andHalvesAlwaysAddUp() {
        // 1 x 10.10 @ 5% = 0.505 -> GST 0.51 -> CGST 0.25 + SGST 0.26
        LineTax t = WholesaleTaxCalculator.compute(d("1"), d("10.10"), d("5"), false);
        assertThat(t.gst()).isEqualByComparingTo("0.51");
        assertThat(t.cgst()).isEqualByComparingTo("0.25");
        assertThat(t.sgst()).isEqualByComparingTo("0.26");
        assertThat(t.cgst().add(t.sgst())).isEqualByComparingTo(t.gst());
    }

    @Test
    void interState_chargesIgstOnly() {
        LineTax t = WholesaleTaxCalculator.compute(d("2.5"), d("420"), d("12"), true);
        assertThat(t.taxable()).isEqualByComparingTo("1050.00");
        assertThat(t.igst()).isEqualByComparingTo("126.00");
        assertThat(t.cgst()).isEqualByComparingTo("0");
        assertThat(t.sgst()).isEqualByComparingTo("0");
        assertThat(t.total()).isEqualByComparingTo("1176.00");
    }

    @Test
    void fractionalQuantity_roundsTaxableToPaise() {
        LineTax t = WholesaleTaxCalculator.compute(d("1.333"), d("99.99"), d("18"), false);
        assertThat(t.taxable()).isEqualByComparingTo("133.29"); // 133.286667
        assertThat(t.gst()).isEqualByComparingTo("23.99");     // 23.9922
        assertThat(t.total()).isEqualByComparingTo("157.28");
    }

    @Test
    void paymentStatus_followsRetailRules() {
        assertThat(WholesaleTaxCalculator.paymentStatus(d("0"), d("100"))).isEqualTo("PENDING");
        assertThat(WholesaleTaxCalculator.paymentStatus(d("40"), d("100"))).isEqualTo("PARTIAL");
        assertThat(WholesaleTaxCalculator.paymentStatus(d("100"), d("100"))).isEqualTo("PAID");
        assertThat(WholesaleTaxCalculator.paymentStatus(d("0"), d("0"))).isEqualTo("PAID");
    }
}
