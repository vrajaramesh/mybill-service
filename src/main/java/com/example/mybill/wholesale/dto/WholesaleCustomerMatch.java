package com.example.mybill.wholesale.dto;

import java.util.List;

/**
 * A possible duplicate found by GET /api/wholesale/customers/duplicate-check.
 * blocking = true for a GSTIN match (save will be rejected); phone/name matches are warnings only.
 */
public record WholesaleCustomerMatch(
    Integer wholesaleCustomerId,
    String customerName,
    String businessName,
    String gstNumber,
    String phone,
    String city,
    Boolean isActive,
    List<String> reasons,
    Boolean blocking
) {}
