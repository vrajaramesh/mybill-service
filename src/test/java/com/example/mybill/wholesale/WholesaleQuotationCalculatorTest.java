package com.example.mybill.wholesale;

import com.example.mybill.wholesale.service.WholesaleQuotationCalculator;
import com.example.mybill.wholesale.service.WholesaleQuotationCalculator.LineInput;
import com.example.mybill.wholesale.service.WholesaleQuotationCalculator.LineResult;
import com.example.mybill.wholesale.service.WholesaleQuotationCalculator.Totals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WholesaleQuotationCalculatorTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void line_appliesDiscountBeforeGst_intraState() {
        // 50 m x 72.00 = 3600.00; 10% discount = 360.00; taxable 3240.00; GST 5% = 162.00 -> 81 + 81
        LineResult r = WholesaleQuotationCalculator.line(new LineInput(d("50"), d("72.00"), d("10"), d("5")), false);
        assertThat(r.gross()).isEqualByComparingTo("3600.00");
        assertThat(r.discount()).isEqualByComparingTo("360.00");
        assertThat(r.taxable()).isEqualByComparingTo("3240.00");
        assertThat(r.cgst()).isEqualByComparingTo("81.00");
        assertThat(r.sgst()).isEqualByComparingTo("81.00");
        assertThat(r.igst()).isEqualByComparingTo("0");
        assertThat(r.total()).isEqualByComparingTo("3402.00");
    }

    @Test
    void line_interState_chargesIgstOnly() {
        LineResult r = WholesaleQuotationCalculator.line(new LineInput(d("12.5"), d("88.40"), null, d("12")), true);
        assertThat(r.taxable()).isEqualByComparingTo("1105.00");
        assertThat(r.igst()).isEqualByComparingTo("132.60");
        assertThat(r.cgst().add(r.sgst())).isEqualByComparingTo("0");
        assertThat(r.total()).isEqualByComparingTo("1237.60");
    }

    @Test
    void line_oddPaisaOfGstGoesToSgst() {
        LineResult r = WholesaleQuotationCalculator.line(new LineInput(d("1"), d("10.10"), d("0"), d("5")), false);
        assertThat(r.gst()).isEqualByComparingTo("0.51");
        assertThat(r.cgst()).isEqualByComparingTo("0.25");
        assertThat(r.sgst()).isEqualByComparingTo("0.26");
    }

    @Test
    void totals_includeOtherChargesWithTheirOwnGst_andRoundToRupee() {
        Totals t = WholesaleQuotationCalculator.totals(List.of(
                new LineInput(d("10"), d("99.99"), d("0"), d("5")),     // taxable 999.90, gst 50.00 (49.995)
                new LineInput(d("3"), d("150.25"), d("5"), d("12"))),   // gross 450.75, disc 22.54, taxable 428.21, gst 51.39
            false, d("250"), d("18"));                                  // freight 250, gst 45.00
        assertThat(t.gross()).isEqualByComparingTo("1450.65");
        assertThat(t.discount()).isEqualByComparingTo("22.54");
        assertThat(t.subtotal()).isEqualByComparingTo("1428.11");
        assertThat(t.otherCharges()).isEqualByComparingTo("250.00");
        assertThat(t.otherChargesGst()).isEqualByComparingTo("45.00");
        assertThat(t.gst()).isEqualByComparingTo("146.39");
        assertThat(t.cgst().add(t.sgst())).isEqualByComparingTo(t.gst());
        // exact 1824.50 -> rounds half-up to 1825.00, round off +0.50
        assertThat(t.roundOff()).isEqualByComparingTo("0.50");
        assertThat(t.grandTotal()).isEqualByComparingTo("1825.00");
    }

    @Test
    void totals_roundOffCanBeNegative() {
        Totals t = WholesaleQuotationCalculator.totals(
            List.of(new LineInput(d("1"), d("100.38"), d("0"), d("0"))), false, null, null);
        assertThat(t.grandTotal()).isEqualByComparingTo("100.00");
        assertThat(t.roundOff()).isEqualByComparingTo("-0.38");
    }

    @Test
    void totals_withNoOtherCharges() {
        Totals t = WholesaleQuotationCalculator.totals(
            List.of(new LineInput(d("100"), d("50"), d("0"), d("5"))), true, d("0"), d("0"));
        assertThat(t.subtotal()).isEqualByComparingTo("5000.00");
        assertThat(t.igst()).isEqualByComparingTo("250.00");
        assertThat(t.grandTotal()).isEqualByComparingTo("5250.00");
        assertThat(t.roundOff()).isEqualByComparingTo("0");
    }
}
