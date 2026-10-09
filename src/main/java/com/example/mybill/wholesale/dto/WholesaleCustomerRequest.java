package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

/**
 * Create/update payload for a wholesale customer. GSTIN may be entered in any case (stored upper-case);
 * state codes are GST state codes ("33" = Tamil Nadu). When shippingSameAsBilling is true (default),
 * shipping fields are ignored and copied from billing. customerType is optional and defaults to RETAILER.
 */
public record WholesaleCustomerRequest(
    @NotBlank(message = "Customer name is required")
    @Size(max = 150, message = "Customer name must be at most 150 characters")
    String customerName,

    @Size(max = 200, message = "Business / firm name must be at most 200 characters")
    String businessName,

    @Pattern(regexp = "^$|^(?i)(WHOLESALE|RETAILER)$", message = "Customer type must be Wholesale or Retailer")
    String customerType,

    @Pattern(regexp = "^$|^(?i)[0-9]{2}[a-z]{5}[0-9]{4}[a-z][1-9a-z]z[0-9a-z]$",
             message = "GST number must be a valid 15-character GSTIN (e.g. 33ABCDE1234F1Z5)")
    String gstNumber,

    @Pattern(regexp = "^$|^\\+?[0-9][0-9 \\-]{8,18}[0-9]$", message = "Phone number must contain 10 to 13 digits")
    String phone,

    @Email(message = "Email address is not valid")
    @Size(max = 150, message = "Email must be at most 150 characters")
    String email,

    String billingAddress,

    @Size(max = 100, message = "City must be at most 100 characters")
    String city,

    @Pattern(regexp = "^$|^[0-9]{2}$", message = "State must be a 2-digit GST state code")
    String stateCode,

    @Pattern(regexp = "^$|^[1-9][0-9]{5}$", message = "PIN code must be 6 digits and cannot start with 0")
    String pinCode,

    Boolean shippingSameAsBilling,

    String shippingAddress,

    @Size(max = 100, message = "Shipping city must be at most 100 characters")
    String shippingCity,

    @Pattern(regexp = "^$|^[0-9]{2}$", message = "Shipping state must be a 2-digit GST state code")
    String shippingStateCode,

    @Pattern(regexp = "^$|^[1-9][0-9]{5}$", message = "Shipping PIN code must be 6 digits and cannot start with 0")
    String shippingPinCode,

    String notes,

    Boolean isActive
) {}
