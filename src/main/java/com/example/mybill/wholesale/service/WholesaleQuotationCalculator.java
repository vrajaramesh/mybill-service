package com.example.mybill.wholesale.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure quotation maths (no database). Rates are exclusive of GST.
 * <pre>
 * line:   gross = qty × rate;  discount = gross × disc%;  taxable = gross − discount;  GST on taxable
 * doc:    subtotal = Σ taxable;  other charges taxed at their own GST %;
 *         grand total = subtotal + other charges + all GST, rounded to the nearest rupee (round_off kept)
 * </pre>
 * Intra-state: CGST + SGST (odd paisa to SGST). Inter-state: IGST.
 */
public final class WholesaleQuotationCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private WholesaleQuotationCalculator() {}

    public record LineInput(BigDecimal quantity, BigDecimal rate, BigDecimal discountPct, BigDecimal gstPct) {}

    public record LineResult(BigDecimal gross, BigDecimal discount, BigDecimal taxable, BigDecimal cgst,
                             BigDecimal sgst, BigDecimal igst, BigDecimal gst, BigDecimal total) {}

    public record Totals(List<LineResult> lines, BigDecimal gross, BigDecimal discount, BigDecimal subtotal,
                         BigDecimal otherCharges, BigDecimal otherChargesGst, BigDecimal cgst, BigDecimal sgst,
                         BigDecimal igst, BigDecimal gst, BigDecimal roundOff, BigDecimal grandTotal) {}

    public static LineResult line(LineInput in, boolean interstate) {
        BigDecimal gross = in.quantity().multiply(in.rate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discPct = in.discountPct() == null ? BigDecimal.ZERO : in.discountPct();
        BigDecimal discount = gross.multiply(discPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal taxable = gross.subtract(discount);
        Tax t = tax(taxable, in.gstPct(), interstate);
        return new LineResult(gross, discount, taxable, t.cgst, t.sgst, t.igst, t.gst, taxable.add(t.gst));
    }

    public static Totals totals(List<LineInput> inputs, boolean interstate,
                                BigDecimal otherChargesAmount, BigDecimal otherChargesGstPct) {
        List<LineResult> lines = new ArrayList<>();
        BigDecimal gross = ZERO, discount = ZERO, subtotal = ZERO, cgst = ZERO, sgst = ZERO, igst = ZERO;
        for (LineInput in : inputs) {
            LineResult r = line(in, interstate);
            lines.add(r);
            gross = gross.add(r.gross());
            discount = discount.add(r.discount());
            subtotal = subtotal.add(r.taxable());
            cgst = cgst.add(r.cgst());
            sgst = sgst.add(r.sgst());
            igst = igst.add(r.igst());
        }
        BigDecimal other = otherChargesAmount == null ? ZERO : otherChargesAmount.setScale(2, RoundingMode.HALF_UP);
        Tax ot = tax(other, otherChargesGstPct == null ? BigDecimal.ZERO : otherChargesGstPct, interstate);
        cgst = cgst.add(ot.cgst);
        sgst = sgst.add(ot.sgst);
        igst = igst.add(ot.igst);
        BigDecimal gst = cgst.add(sgst).add(igst);

        BigDecimal exact = subtotal.add(other).add(gst);
        BigDecimal grand = exact.setScale(0, RoundingMode.HALF_UP).setScale(2);
        return new Totals(lines, gross, discount, subtotal, other, ot.gst, cgst, sgst, igst, gst,
            grand.subtract(exact), grand);
    }

    private record Tax(BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal gst) {}

    private static Tax tax(BigDecimal taxable, BigDecimal gstPct, boolean interstate) {
        BigDecimal gst = taxable.multiply(gstPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        if (interstate) return new Tax(ZERO, ZERO, gst, gst);
        BigDecimal cgst = gst.divide(new BigDecimal("2"), 2, RoundingMode.HALF_DOWN);
        return new Tax(cgst, gst.subtract(cgst), ZERO, gst);
    }
}
