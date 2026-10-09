package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.WholesalePriceQuoteResponse;
import com.example.mybill.wholesale.dto.WholesalePriceRuleRequest;
import com.example.mybill.wholesale.dto.WholesalePriceRuleResponse;
import com.example.mybill.wholesale.entity.WholesaleProduct;
import com.example.mybill.wholesale.entity.WholesaleProductPriceRule;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleProductPriceRuleRepository;
import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Quantity-based wholesale pricing: Product + quantity range (+ optional date window) -> price per unit.
 * Completely separate from retail selling_price; retail pricing is never read or changed here.
 */
@Service
public class WholesalePricingService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired private WholesaleProductRepository productRepository;
    @Autowired private WholesaleProductPriceRuleRepository ruleRepository;

    @Transactional(readOnly = true)
    public List<WholesalePriceRuleResponse> listRules(Integer productId, boolean includeInactive) {
        requireProduct(productId);
        return ruleRepository.findByProduct_WholesaleProductIdOrderByMinQuantityAscEffectiveFromAsc(productId).stream()
            .filter(r -> includeInactive || Boolean.TRUE.equals(r.getIsActive()))
            .map(WholesalePricingService::toResponse)
            .toList();
    }

    @Transactional
    public WholesalePriceRuleResponse createRule(Integer productId, WholesalePriceRuleRequest request) {
        WholesaleProduct product = lockProduct(productId);
        WholesaleProductPriceRule rule = new WholesaleProductPriceRule();
        rule.setProduct(product);
        apply(rule, request);
        assertNoOverlap(product, rule);
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public WholesalePriceRuleResponse updateRule(Integer productId, Integer ruleId, WholesalePriceRuleRequest request) {
        WholesaleProduct product = lockProduct(productId);
        WholesaleProductPriceRule rule = findRule(productId, ruleId);
        apply(rule, request);
        assertNoOverlap(product, rule);
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Integer productId, Integer ruleId) {
        lockProduct(productId);
        ruleRepository.delete(findRule(productId, ruleId));
    }

    /**
     * Price for buying {@code quantity} units of the product on {@code date} (default: today in India).
     * Amounts use the same tax-exclusive GST maths as wholesale purchases.
     */
    @Transactional(readOnly = true)
    public WholesalePriceQuoteResponse quote(Integer productId, BigDecimal quantity, LocalDate date, boolean interstate) {
        if (quantity == null || quantity.signum() <= 0) {
            throw WholesaleException.badRequest("Quantity must be greater than 0");
        }
        if (quantity.scale() > 3) {
            throw WholesaleException.badRequest("Quantity allows at most 3 decimals");
        }
        WholesaleProduct product = requireProduct(productId);
        if (!Boolean.TRUE.equals(product.getIsActive())) {
            throw WholesaleException.badRequest("'" + product.getProductName() + "' is inactive");
        }
        LocalDate priceDate = date != null ? date : LocalDate.now(BUSINESS_ZONE);

        WholesaleProductPriceRule rule = ruleRepository.findApplicable(productId, quantity, priceDate).stream()
            .findFirst()
            .orElseThrow(() -> WholesaleException.notFound("No active price rule covers " + plain(quantity) + " "
                + product.getUnit() + " of '" + product.getProductName() + "' on " + priceDate));

        boolean ruleGst = rule.getGstPct() != null;
        BigDecimal gstPct = ruleGst ? rule.getGstPct() : product.getGstPct();
        WholesaleTaxCalculator.LineTax tax = WholesaleTaxCalculator.compute(quantity, rule.getPrice(), gstPct, interstate);

        return new WholesalePriceQuoteResponse(
            product.getWholesaleProductId(), product.getProductName(), product.getUnit(), quantity, priceDate,
            rule.getPriceRuleId(), rule.getMinQuantity(), rule.getMaxQuantity(), rule.getPrice(), gstPct,
            ruleGst ? "RULE" : "PRODUCT", interstate, tax.taxable(), tax.cgst(), tax.sgst(), tax.igst(), tax.gst(),
            tax.total(), product.getAvailableQuantity(), product.getAvailableQuantity().compareTo(quantity) >= 0);
    }

    private void apply(WholesaleProductPriceRule rule, WholesalePriceRuleRequest r) {
        if (r.maxQuantity() != null && r.maxQuantity().compareTo(r.minQuantity()) < 0) {
            throw WholesaleException.badRequest("Maximum quantity must be greater than or equal to minimum quantity");
        }
        if (r.effectiveFrom() != null && r.effectiveTo() != null && r.effectiveTo().isBefore(r.effectiveFrom())) {
            throw WholesaleException.badRequest("Effective-to date cannot be before effective-from date");
        }
        rule.setMinQuantity(r.minQuantity());
        rule.setMaxQuantity(r.maxQuantity());
        rule.setPrice(r.price());
        rule.setGstPct(r.gstPct());
        rule.setIsActive(r.isActive() == null || r.isActive());
        rule.setEffectiveFrom(r.effectiveFrom());
        rule.setEffectiveTo(r.effectiveTo());
        rule.setNotes(WholesaleProductService.blankToNull(r.notes()));
    }

    /**
     * Two active rules of the same product may not overlap in BOTH quantity and effective dates,
     * so a price lookup always resolves to exactly one rule. Inactive rules are never checked.
     */
    private void assertNoOverlap(WholesaleProduct product, WholesaleProductPriceRule candidate) {
        if (!Boolean.TRUE.equals(candidate.getIsActive())) return;
        for (WholesaleProductPriceRule other : ruleRepository.findByProduct_WholesaleProductIdAndIsActiveTrue(product.getWholesaleProductId())) {
            if (other.getPriceRuleId().equals(candidate.getPriceRuleId())) continue;
            if (quantitiesOverlap(candidate, other) && datesOverlap(candidate, other)) {
                throw WholesaleException.conflict("Overlaps existing price rule #" + other.getPriceRuleId() + " ("
                    + describeRange(other) + " " + product.getUnit() + describeDates(other)
                    + "). Adjust the quantity range or dates, or deactivate that rule first.");
            }
        }
    }

    static boolean quantitiesOverlap(WholesaleProductPriceRule a, WholesaleProductPriceRule b) {
        boolean aStartsBeforeBEnds = b.getMaxQuantity() == null || a.getMinQuantity().compareTo(b.getMaxQuantity()) <= 0;
        boolean bStartsBeforeAEnds = a.getMaxQuantity() == null || b.getMinQuantity().compareTo(a.getMaxQuantity()) <= 0;
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }

    static boolean datesOverlap(WholesaleProductPriceRule a, WholesaleProductPriceRule b) {
        boolean aStartsBeforeBEnds = a.getEffectiveFrom() == null || b.getEffectiveTo() == null
            || !a.getEffectiveFrom().isAfter(b.getEffectiveTo());
        boolean bStartsBeforeAEnds = b.getEffectiveFrom() == null || a.getEffectiveTo() == null
            || !b.getEffectiveFrom().isAfter(a.getEffectiveTo());
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }

    private static String describeRange(WholesaleProductPriceRule r) {
        return r.getMaxQuantity() == null ? plain(r.getMinQuantity()) + "+" : plain(r.getMinQuantity()) + "–" + plain(r.getMaxQuantity());
    }

    private static String describeDates(WholesaleProductPriceRule r) {
        if (r.getEffectiveFrom() == null && r.getEffectiveTo() == null) return "";
        return ", " + (r.getEffectiveFrom() != null ? r.getEffectiveFrom() : "…") + " to "
            + (r.getEffectiveTo() != null ? r.getEffectiveTo() : "…");
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private WholesaleProduct requireProduct(Integer productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> WholesaleException.notFound("Wholesale product #" + productId + " not found"));
    }

    private WholesaleProduct lockProduct(Integer productId) {
        return productRepository.findByIdForUpdate(productId)
            .orElseThrow(() -> WholesaleException.notFound("Wholesale product #" + productId + " not found"));
    }

    private WholesaleProductPriceRule findRule(Integer productId, Integer ruleId) {
        return ruleRepository.findByPriceRuleIdAndProduct_WholesaleProductId(ruleId, productId)
            .orElseThrow(() -> WholesaleException.notFound("Price rule #" + ruleId + " not found for this product"));
    }

    static WholesalePriceRuleResponse toResponse(WholesaleProductPriceRule r) {
        return new WholesalePriceRuleResponse(
            r.getPriceRuleId(), r.getProduct().getWholesaleProductId(), r.getMinQuantity(), r.getMaxQuantity(),
            r.getPrice(), r.getGstPct(), r.getIsActive(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getNotes(),
            r.getCreatedAt(), r.getUpdatedAt());
    }
}
