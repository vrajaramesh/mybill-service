package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;

/** estimated_delivery_date = today + lead_time_days + transit_days (null when not serviceable). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QuoteResponse(String designCode, String designName, String sizeCode, Integer leadTimeDays,
                            PriceBreakdown breakdown, boolean serviceable, Integer transitDays,
                            LocalDate estimatedDeliveryDate) {}
