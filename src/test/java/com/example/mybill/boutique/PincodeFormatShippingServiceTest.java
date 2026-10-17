package com.example.mybill.boutique;

import com.example.mybill.boutique.service.PincodeFormatShippingService;
import com.example.mybill.boutique.service.ShippingService.ShippingEstimate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PincodeFormatShippingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private final PincodeFormatShippingService shipping = new PincodeFormatShippingService(5);

    @Test
    void validPincode_isServiceable_withLeadTimePlusTransit() {
        ShippingEstimate e = shipping.estimate("500001", 7, TODAY);
        assertThat(e.serviceable()).isTrue();
        assertThat(e.transitDays()).isEqualTo(5);
        assertThat(e.estimatedDeliveryDate()).isEqualTo(LocalDate.of(2026, 10, 29));   // 17 + 7 + 5
    }

    @Test
    void noPincodeYet_stillEstimates() {
        assertThat(shipping.estimate(null, 3, TODAY).estimatedDeliveryDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(shipping.estimate(" ", 0, TODAY).serviceable()).isTrue();
    }

    @Test
    void malformedPincode_isNotServiceable() {
        for (String bad : new String[]{"012345", "50001", "5000011", "50000A"}) {
            ShippingEstimate e = shipping.estimate(bad, 7, TODAY);
            assertThat(e.serviceable()).as(bad).isFalse();
            assertThat(e.estimatedDeliveryDate()).isNull();
            assertThat(e.reason()).isEqualTo("INVALID_PINCODE");
        }
    }

    @Test
    void transitDaysAreConfigurable() {
        assertThat(new PincodeFormatShippingService(2).estimate("110001", 4, TODAY).estimatedDeliveryDate())
            .isEqualTo(LocalDate.of(2026, 10, 23));
    }
}
