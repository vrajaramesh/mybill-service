package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.WholesalePriceQuoteResponse;
import com.example.mybill.wholesale.entity.WholesaleProductPriceRule;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleProductPriceRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only wholesale price facts for the customer messaging assistant: today's active quantity slabs, the minimum
 * order quantity and the price for a requested quantity — all from wholesale price rules via WholesalePricingService.
 */
@Service
public class WholesalePriceLookupService {

    public record PriceSlab(BigDecimal minQuantity, BigDecimal maxQuantity, BigDecimal pricePerUnit, BigDecimal gstPct) {}

    /** Amounts exclude GST except totalWithGst (intra-state; the GST total is the same inter-state). */
    public record QuantityPrice(BigDecimal quantity, BigDecimal pricePerUnit, BigDecimal gstPct, BigDecimal taxableAmount,
                                BigDecimal gstAmount, BigDecimal totalWithGst) {}

    /** PRICED, BELOW_MINIMUM, NO_PRICE_FOR_QUANTITY, UNIT_MISMATCH, or null when no quantity was asked. */
    public record PriceInfo(String unit, List<PriceSlab> slabs, BigDecimal minimumOrderQuantity,
                            String requestedStatus, QuantityPrice requested) {}

    @Autowired private WholesaleProductPriceRuleRepository ruleRepository;
    @Autowired private WholesalePricingService pricingService;

    @Transactional(readOnly = true)
    public PriceInfo lookup(Integer productId, String productUnit, BigDecimal quantity, String customerUnit) {
        LocalDate today = LocalDate.now(WholesalePricingService.BUSINESS_ZONE);
        List<PriceSlab> slabs = ruleRepository.findByProduct_WholesaleProductIdAndIsActiveTrue(productId).stream()
            .filter(r -> (r.getEffectiveFrom() == null || !r.getEffectiveFrom().isAfter(today))
                && (r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(today)))
            .sorted(Comparator.comparing(WholesaleProductPriceRule::getMinQuantity))
            .map(r -> new PriceSlab(strip(r.getMinQuantity()), strip(r.getMaxQuantity()), r.getPrice(), r.getGstPct()))
            .toList();
        BigDecimal minimum = slabs.isEmpty() ? null : slabs.get(0).minQuantity();

        if (quantity == null || quantity.signum() <= 0) return new PriceInfo(productUnit, slabs, minimum, null, null);
        if (customerUnit != null && !sameUnit(customerUnit, productUnit)) {
            return new PriceInfo(productUnit, slabs, minimum, "UNIT_MISMATCH", null);
        }
        if (minimum != null && quantity.compareTo(minimum) < 0) {
            return new PriceInfo(productUnit, slabs, minimum, "BELOW_MINIMUM", null);
        }
        try {
            WholesalePriceQuoteResponse q = pricingService.quote(productId, quantity.scale() > 3 ? quantity.setScale(3, RoundingMode.HALF_UP) : quantity, today, false);
            return new PriceInfo(productUnit, slabs, minimum, "PRICED",
                new QuantityPrice(strip(q.quantity()), q.unitPrice(), q.gstPct(), q.taxableAmount(), q.gstAmount(), q.totalAmount()));
        } catch (WholesaleException e) {
            return new PriceInfo(productUnit, slabs, minimum, "NO_PRICE_FOR_QUANTITY", null);
        }
    }

    /** Loose unit comparison: meters / mtr / m, pieces / pcs, kg / kgs, … Unknown units are treated as the same. */
    static boolean sameUnit(String a, String b) {
        String x = unitFamily(a), y = unitFamily(b);
        return x == null || y == null || x.equals(y);
    }

    static String unitFamily(String unit) {
        if (unit == null) return null;
        String u = unit.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (u.isEmpty()) return null;
        if (u.matches("m|mt|mts|mtr|mtrs|meter|meters|metre|metres")) return "METER";
        if (u.matches("pc|pcs|piece|pieces|nos|no|number|numbers|unit|units")) return "PIECE";
        if (u.matches("kg|kgs|kilo|kilos|kilogram|kilograms")) return "KG";
        if (u.matches("g|gm|gms|gram|grams")) return "GRAM";
        if (u.matches("yd|yds|yard|yards")) return "YARD";
        if (u.matches("roll|rolls|than|thaan|thans")) return "ROLL";
        if (u.matches("set|sets")) return "SET";
        if (u.matches("dozen|dozens|doz")) return "DOZEN";
        return null;
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : plain(v);
    }

    /** Without trailing zeros and never in exponent form (100, not 1E+2). */
    static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
