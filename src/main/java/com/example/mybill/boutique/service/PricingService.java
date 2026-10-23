package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.FabricResponse;
import com.example.mybill.boutique.dto.PriceBreakdown;
import com.example.mybill.boutique.dto.SelectedOptionDto;
import com.example.mybill.boutique.entity.BoutiqueDesign;
import com.example.mybill.boutique.entity.CustomisationOption;
import com.example.mybill.boutique.exception.AgentApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Server-side boutique pricing (INR, BigDecimal, 2 decimals). The agent never computes prices itself.
 *
 *   unit_price = base_price + sum(selected option price_delta)
 *   subtotal   = unit_price × qty
 *   delivery   = flat charge, waived when subtotal >= free-above threshold
 *   total      = subtotal + delivery
 *
 * Pure: callers load the design and options; this class only validates and calculates.
 */
@Service
public class PricingService {

    public static final String CURRENCY = "INR";

    private final BigDecimal deliveryFlatCharge;
    private final BigDecimal freeDeliveryAbove;

    public PricingService(@Value("${boutique.delivery.flat-charge:150}") BigDecimal deliveryFlatCharge,
                          @Value("${boutique.delivery.free-above:2500}") BigDecimal freeDeliveryAbove) {
        this.deliveryFlatCharge = money(deliveryFlatCharge);
        this.freeDeliveryAbove = money(freeDeliveryAbove);
    }

    /**
     * @param design           the design being ordered
     * @param requestedIds     option ids exactly as the client sent them (may be null/empty)
     * @param candidateOptions options loaded for those ids (any design, active or not; validated here)
     * @param qty              quantity, >= 1
     */
    public PriceBreakdown price(BoutiqueDesign design, Collection<Integer> requestedIds,
                                Collection<CustomisationOption> candidateOptions, int qty) {
        if (design == null || !Boolean.TRUE.equals(design.getIsActive())) {
            throw AgentApiException.unprocessable("DESIGN_INACTIVE", "This design is not available for ordering",
                design == null ? Map.of() : Map.of("design_code", String.valueOf(design.getCode())));
        }
        if (design.getBasePrice() == null) {
            throw AgentApiException.unprocessable("DESIGN_NOT_PRICED", "This design has no price yet",
                Map.of("design_code", String.valueOf(design.getCode())));
        }
        if (qty < 1) {
            throw AgentApiException.unprocessable("INVALID_QUANTITY", "qty must be at least 1", Map.of("qty", qty));
        }

        List<CustomisationOption> selected = validateOptions(design, requestedIds, candidateOptions);

        BigDecimal optionsTotal = selected.stream().map(o -> money(o.getPriceDelta()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal basePrice = money(design.getBasePrice());
        BigDecimal unitPrice = basePrice.add(optionsTotal);
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(qty));
        BigDecimal delivery = subtotal.compareTo(freeDeliveryAbove) >= 0 ? money(BigDecimal.ZERO) : deliveryFlatCharge;

        List<SelectedOptionDto> optionDtos = selected.stream()
            .map(o -> new SelectedOptionDto(o.getOptionId(), o.getOptionGroup(), o.getName(), money(o.getPriceDelta())))
            .toList();
        return new PriceBreakdown(basePrice, optionDtos, money(optionsTotal), money(unitPrice), qty, money(subtotal),
            delivery, freeDeliveryAbove, money(subtotal.add(delivery)), CURRENCY);
    }

    /**
     * Fabric by the metre: subtotal = price_per_metre x metres (metres in steps of 0.25, within stock);
     * delivery as for garments. unit_price = price_per_metre, qty = 1.
     */
    public PriceBreakdown priceFabric(FabricResponse fabric, BigDecimal metres) {
        if (metres == null || metres.compareTo(new BigDecimal("0.5")) < 0
                || metres.multiply(BigDecimal.valueOf(4)).stripTrailingZeros().scale() > 0) {
            throw AgentApiException.unprocessable("INVALID_METRES", "metres must be at least 0.5, in steps of 0.25",
                Map.of("metres", String.valueOf(metres)));
        }
        if (metres.compareTo(fabric.availableMetres()) > 0) {
            throw AgentApiException.unprocessable("INSUFFICIENT_STOCK", "Only " + fabric.availableMetres().stripTrailingZeros().toPlainString()
                + " m of " + fabric.name() + " in stock", Map.of("available_metres", fabric.availableMetres(), "code", fabric.code()));
        }
        BigDecimal price = money(fabric.pricePerMetre());
        BigDecimal subtotal = money(price.multiply(metres));
        BigDecimal delivery = subtotal.compareTo(freeDeliveryAbove) >= 0 ? money(BigDecimal.ZERO) : deliveryFlatCharge;
        return new PriceBreakdown(price, List.of(), money(BigDecimal.ZERO), price, 1, subtotal, delivery,
            freeDeliveryAbove, money(subtotal.add(delivery)), CURRENCY);
    }

    /** Breakdown for a goods cart whose lines are already priced: delivery on the subtotal as for every order. */
    public PriceBreakdown priceSubtotal(BigDecimal subtotalIn) {
        BigDecimal subtotal = money(subtotalIn);
        BigDecimal delivery = subtotal.compareTo(freeDeliveryAbove) >= 0 ? money(BigDecimal.ZERO) : deliveryFlatCharge;
        return new PriceBreakdown(subtotal, List.of(), money(BigDecimal.ZERO), subtotal, 1, subtotal, delivery,
            freeDeliveryAbove, money(subtotal.add(delivery)), CURRENCY);
    }

    /** Every requested option must exist, be active, belong to this design (or be global), one per group. */
    private List<CustomisationOption> validateOptions(BoutiqueDesign design, Collection<Integer> requestedIds,
                                                      Collection<CustomisationOption> candidates) {
        if (requestedIds == null || requestedIds.isEmpty()) return List.of();

        Set<Integer> uniqueIds = new LinkedHashSet<>(requestedIds);
        if (uniqueIds.size() != requestedIds.size()) {
            throw AgentApiException.unprocessable("DUPLICATE_OPTION", "The same option was selected twice",
                Map.of("option_ids", List.copyOf(requestedIds)));
        }

        Map<Integer, CustomisationOption> byId = new HashMap<>();
        for (CustomisationOption o : candidates) byId.put(o.getOptionId(), o);

        List<CustomisationOption> selected = new ArrayList<>();
        Map<String, Integer> groupOwner = new HashMap<>();
        for (Integer id : uniqueIds) {
            CustomisationOption o = byId.get(id);
            boolean belongs = o != null && (o.getDesignId() == null || o.getDesignId().equals(design.getDesignId()));
            if (!belongs || !Boolean.TRUE.equals(o.getIsActive())) {
                throw AgentApiException.unprocessable("INVALID_OPTION",
                    "Option " + id + " is not available for design " + design.getCode(), Map.of("option_id", id));
            }
            Integer previous = groupOwner.putIfAbsent(o.getOptionGroup(), id);
            if (previous != null) {
                throw AgentApiException.unprocessable("OPTION_GROUP_CONFLICT",
                    "Only one " + o.getOptionGroup() + " option can be selected",
                    Map.of("option_group", o.getOptionGroup(), "option_ids", List.of(previous, id)));
            }
            selected.add(o);
        }
        return selected;
    }

    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
