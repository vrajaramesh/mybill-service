package com.example.mybill.wholesale.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GST for wholesale purchase lines. Rates are tax-exclusive (B2B): taxable = qty x rate, GST on top.
 * Intra-state splits GST into CGST + SGST (any odd paisa goes to SGST so the halves always add up);
 * inter-state charges IGST.
 */
public final class WholesaleTaxCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private WholesaleTaxCalculator() {}

    public record LineTax(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                          BigDecimal gst, BigDecimal total) {}

    public static LineTax compute(BigDecimal quantity, BigDecimal rate, BigDecimal gstPct, boolean interstate) {
        BigDecimal taxable = quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gst = taxable.multiply(gstPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (interstate) {
            return new LineTax(taxable, zero, zero, gst, gst, taxable.add(gst));
        }
        BigDecimal cgst = gst.divide(TWO, 2, RoundingMode.HALF_DOWN);
        BigDecimal sgst = gst.subtract(cgst);
        return new LineTax(taxable, cgst, sgst, zero, gst, taxable.add(gst));
    }

    /** Same rules as retail purchases: PAID when fully paid, PARTIAL when something is paid, else PENDING. */
    public static String paymentStatus(BigDecimal paid, BigDecimal total) {
        if (paid.compareTo(total) >= 0 && total.signum() > 0) return "PAID";
        if (paid.signum() > 0) return "PARTIAL";
        return total.signum() == 0 ? "PAID" : "PENDING";
    }
}
