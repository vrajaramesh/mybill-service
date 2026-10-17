package com.example.mybill.boutique.service;

import java.time.LocalDate;

/**
 * Delivery serviceability and date estimate for a destination pincode.
 *
 * TODO: add a Shiprocket implementation (serviceability API: courier availability, COD and the
 *  courier's estimated transit days for pickup pincode -> delivery pincode) and make it the primary bean.
 *  Until then {@link PincodeFormatShippingService} only checks the pincode format.
 */
public interface ShippingService {

    /**
     * @param pincode        destination pincode, or null when not known yet (quote without address)
     * @param leadTimeDays   stitching time of the design
     * @param today          order/quote date (Asia/Kolkata)
     */
    ShippingEstimate estimate(String pincode, int leadTimeDays, LocalDate today);

    /** serviceable=false means we cannot deliver there; estimatedDeliveryDate is then null. */
    record ShippingEstimate(boolean serviceable, int transitDays, LocalDate estimatedDeliveryDate, String reason) {}
}
