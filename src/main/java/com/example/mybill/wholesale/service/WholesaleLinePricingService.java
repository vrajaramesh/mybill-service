package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.entity.WholesaleProduct;
import com.example.mybill.wholesale.entity.WholesaleProductPriceRule;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleProductPriceRuleRepository;
import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Prices one wholesale document line (quotation or sales receipt) from the product's quantity price rules.
 * The result is stored on the line; callers must never re-price saved lines. Retail prices are never used.
 */
@Service
public class WholesaleLinePricingService {

    @Autowired private WholesaleProductRepository productRepository;
    @Autowired private WholesaleProductPriceRuleRepository ruleRepository;

    /**
     * @param rate     null = use the slab covering {@code quantity} on {@code priceDate}; otherwise kept as given
     * @param gstPct   null = slab GST %, else the product's GST %
     * @param stockNote appended to the "not enough stock" warning (differs per document)
     */
    public record PricedLine(WholesaleProduct product, WholesaleProductPriceRule rule, BigDecimal rate, String rateSource,
                             BigDecimal ruleRate, BigDecimal gstPct) {}

    public PricedLine price(Integer productId, BigDecimal quantity, BigDecimal rate, BigDecimal gstPct, LocalDate priceDate,
                            int lineNo, List<String> warnings, String stockNote) {
        WholesaleProduct product = productRepository.findById(productId)
            .orElseThrow(() -> WholesaleException.badRequest("Item " + lineNo + ": wholesale product #" + productId + " not found"));
        if (!Boolean.TRUE.equals(product.getIsActive())) {
            throw WholesaleException.badRequest("Item " + lineNo + ": '" + product.getProductName() + "' is inactive");
        }

        WholesaleProductPriceRule rule = ruleRepository.findApplicable(product.getWholesaleProductId(), quantity, priceDate)
            .stream().findFirst().orElse(null);
        BigDecimal finalRate = rate;
        if (finalRate == null) {
            if (rule == null) {
                throw WholesaleException.badRequest("Item " + lineNo + ": no price rule covers "
                    + quantity.stripTrailingZeros().toPlainString() + " " + product.getUnit() + " of '"
                    + product.getProductName() + "' on " + priceDate + ". Add a price rule or enter a rate.");
            }
            finalRate = rule.getPrice();
        }
        boolean fromRule = rule != null && finalRate.compareTo(rule.getPrice()) == 0;
        BigDecimal finalGst = gstPct != null ? gstPct
            : rule != null && rule.getGstPct() != null ? rule.getGstPct() : product.getGstPct();

        if (product.getAvailableQuantity().compareTo(quantity) < 0) {
            warnings.add("Item " + lineNo + ": only " + product.getAvailableQuantity().stripTrailingZeros().toPlainString()
                + " " + product.getUnit() + " of '" + product.getProductName() + "' in stock (" + stockNote + ").");
        }
        return new PricedLine(product, rule, finalRate, fromRule ? "RULE" : "MANUAL",
            rule != null ? rule.getPrice() : null, finalGst);
    }
}
