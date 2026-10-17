package com.example.mybill.boutique.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Interim ShippingService: every valid Indian pincode (6 digits, not starting with 0) is serviceable, with a flat,
 * configurable transit time. estimated delivery = today + lead_time_days + transit days (calendar days, no holidays).
 * Replace with a Shiprocket-backed implementation later (see ShippingService).
 */
@Service
public class PincodeFormatShippingService implements ShippingService {

    static final Pattern INDIAN_PINCODE = Pattern.compile("^[1-9][0-9]{5}$");

    private final int transitDays;

    public PincodeFormatShippingService(@Value("${boutique.delivery.transit-days:5}") int transitDays) {
        this.transitDays = transitDays;
    }

    @Override
    public ShippingEstimate estimate(String pincode, int leadTimeDays, LocalDate today) {
        if (pincode != null && !pincode.isBlank() && !INDIAN_PINCODE.matcher(pincode.trim()).matches()) {
            return new ShippingEstimate(false, transitDays, null, "INVALID_PINCODE");
        }
        return new ShippingEstimate(true, transitDays, today.plusDays((long) Math.max(leadTimeDays, 0) + transitDays), null);
    }
}
