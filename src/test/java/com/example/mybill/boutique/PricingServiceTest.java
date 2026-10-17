package com.example.mybill.boutique;

import com.example.mybill.boutique.dto.PriceBreakdown;
import com.example.mybill.boutique.entity.BoutiqueDesign;
import com.example.mybill.boutique.entity.CustomisationOption;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.PricingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingServiceTest {

    private static final int DESIGN_ID = 10;

    // Flat ₹100 delivery, free from ₹2500 subtotal.
    private final PricingService pricing = new PricingService(new BigDecimal("100"), new BigDecimal("2500"));

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static BoutiqueDesign design(String basePrice, boolean active) {
        BoutiqueDesign design = new BoutiqueDesign();
        design.setDesignId(DESIGN_ID);
        design.setCode("KUR-101");
        design.setGarmentType("Kurta");
        design.setBasePrice(d(basePrice));
        design.setIsActive(active);
        return design;
    }

    private static CustomisationOption option(int id, Integer designId, String group, String delta, boolean active) {
        return new CustomisationOption(id, designId, group, group + " " + id, d(delta), active);
    }

    private static String code(Throwable t) { return ((AgentApiException) t).getCode(); }

    @Test
    void noOptions_basePlusDelivery() {
        PriceBreakdown b = pricing.price(design("1299", true), List.of(), List.of(), 1);
        assertThat(b.unitPrice()).isEqualByComparingTo("1299.00");
        assertThat(b.subtotal()).isEqualByComparingTo("1299.00");
        assertThat(b.deliveryCharge()).isEqualByComparingTo("100.00");
        assertThat(b.total()).isEqualByComparingTo("1399.00");
        assertThat(b.total().scale()).isEqualTo(2);
        assertThat(b.currency()).isEqualTo("INR");
    }

    @Test
    void optionsAddTheirPriceDelta_designAndGlobalOptions() {
        List<CustomisationOption> options = List.of(
            option(1, null, "SLEEVE", "150.00", true),       // global
            option(2, DESIGN_ID, "NECK", "200.50", true),    // this design
            option(3, null, "LENGTH", "0", true));
        PriceBreakdown b = pricing.price(design("1299", true), List.of(1, 2, 3), options, 1);

        assertThat(b.optionsTotal()).isEqualByComparingTo("350.50");
        assertThat(b.unitPrice()).isEqualByComparingTo("1649.50");
        assertThat(b.total()).isEqualByComparingTo("1749.50");
        assertThat(b.options()).extracting("optionId").containsExactly(1, 2, 3);
        assertThat(b.options()).extracting("optionGroup").containsExactly("SLEEVE", "NECK", "LENGTH");
    }

    @Test
    void qtyMultipliesUnitPrice() {
        PriceBreakdown b = pricing.price(design("1000", true), List.of(1), List.of(option(1, null, "SLEEVE", "150", true)), 2);
        assertThat(b.unitPrice()).isEqualByComparingTo("1150.00");
        assertThat(b.subtotal()).isEqualByComparingTo("2300.00");
        assertThat(b.total()).isEqualByComparingTo("2400.00");
    }

    @Test
    void deliveryIsFreeFromTheThreshold() {
        assertThat(pricing.price(design("2499.99", true), List.of(), List.of(), 1).deliveryCharge())
            .isEqualByComparingTo("100.00");
        PriceBreakdown atThreshold = pricing.price(design("2500", true), List.of(), List.of(), 1);
        assertThat(atThreshold.deliveryCharge()).isEqualByComparingTo("0.00");
        assertThat(atThreshold.total()).isEqualByComparingTo("2500.00");
        assertThat(atThreshold.freeDeliveryAbove()).isEqualByComparingTo("2500.00");
    }

    @Test
    void optionOfAnotherDesign_isRejected() {
        assertThatThrownBy(() -> pricing.price(design("1000", true), List.of(7),
                List.of(option(7, 99, "NECK", "100", true)), 1))
            .isInstanceOf(AgentApiException.class)
            .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_OPTION"));
    }

    @Test
    void unknownOptionId_isRejected() {
        assertThatThrownBy(() -> pricing.price(design("1000", true), List.of(42), List.of(), 1))
            .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_OPTION"));
    }

    @Test
    void inactiveOption_isRejected() {
        assertThatThrownBy(() -> pricing.price(design("1000", true), List.of(1),
                List.of(option(1, null, "SLEEVE", "150", false)), 1))
            .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_OPTION"));
    }

    @Test
    void twoOptionsInTheSameGroup_areRejected() {
        List<CustomisationOption> options = List.of(
            option(1, null, "SLEEVE", "0", true),
            option(2, null, "SLEEVE", "150", true));
        assertThatThrownBy(() -> pricing.price(design("1000", true), List.of(1, 2), options, 1))
            .isInstanceOf(AgentApiException.class)
            .satisfies(t -> {
                assertThat(code(t)).isEqualTo("OPTION_GROUP_CONFLICT");
                assertThat(((AgentApiException) t).getDetails()).containsEntry("option_group", "SLEEVE");
            });
    }

    @Test
    void sameOptionTwice_isRejected() {
        assertThatThrownBy(() -> pricing.price(design("1000", true), List.of(1, 1),
                List.of(option(1, null, "SLEEVE", "150", true)), 1))
            .satisfies(t -> assertThat(code(t)).isEqualTo("DUPLICATE_OPTION"));
    }

    @Test
    void inactiveDesign_isRejected() {
        assertThatThrownBy(() -> pricing.price(design("1000", false), List.of(), List.of(), 1))
            .isInstanceOf(AgentApiException.class)
            .satisfies(t -> assertThat(code(t)).isEqualTo("DESIGN_INACTIVE"));
    }

    @Test
    void designWithoutPrice_isRejected() {
        BoutiqueDesign design = design("1", true);
        design.setBasePrice(null);
        assertThatThrownBy(() -> pricing.price(design, List.of(), List.of(), 1))
            .satisfies(t -> assertThat(code(t)).isEqualTo("DESIGN_NOT_PRICED"));
    }

    @Test
    void amountsAreRoundedToPaise() {
        PriceBreakdown b = pricing.price(design("999.995", true), List.of(), List.of(), 1);
        assertThat(b.basePrice()).isEqualByComparingTo("1000.00");
        assertThat(b.basePrice().scale()).isEqualTo(2);
    }
}
