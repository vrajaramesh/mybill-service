package com.example.mybill.wholesale.dto;

import java.time.LocalDateTime;

public record WholesaleCustomerResponse(
    Integer wholesaleCustomerId,
    String customerName,
    String businessName,
    String customerType,
    String customerTypeLabel,
    String gstNumber,
    String phone,
    String email,
    String billingAddress,
    String city,
    String stateCode,
    String stateName,
    String pinCode,
    Boolean shippingSameAsBilling,
    String shippingAddress,
    String shippingCity,
    String shippingStateCode,
    String shippingStateName,
    String shippingPinCode,
    String notes,
    Boolean isActive,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
