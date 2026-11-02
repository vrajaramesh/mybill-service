package com.example.mybill.boutique.service;

import com.example.mybill.boutique.courier.DelhiveryClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * ShippingService with Delhivery's pincode serviceability on top of the format check and date estimate of
 * {@link PincodeFormatShippingService}. A pincode Delhivery says it can't deliver to (prepaid) is not serviceable;
 * if Delhivery is not set up or can't be reached, the format check alone decides (ordering never breaks).
 */
@Service
@Primary
public class DelhiveryShippingService implements ShippingService {

    @Autowired private PincodeFormatShippingService formatCheck;
    @Autowired private DelhiveryClient delhivery;

    @Override
    public ShippingEstimate estimate(String pincode, int leadTimeDays, LocalDate today) {
        ShippingEstimate base = formatCheck.estimate(pincode, leadTimeDays, today);
        if (!base.serviceable() || pincode == null || pincode.isBlank()) return base;
        if (delhivery.serviceable(pincode.trim()).orElse(true)) return base;
        return new ShippingEstimate(false, base.transitDays(), null, "COURIER_NOT_SERVICEABLE");
    }
}
