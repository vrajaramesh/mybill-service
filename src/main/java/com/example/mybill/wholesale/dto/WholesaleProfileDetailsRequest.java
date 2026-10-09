package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

/** Settings → Wholesale Business Profile. Mandatory: firm name, address, state, phone. */
public record WholesaleProfileDetailsRequest(
    @NotBlank(message = "Firm name is required")
    @Size(max = 200, message = "Firm name must be at most 200 characters")
    String firmName,

    @NotBlank(message = "Firm address is required")
    @Size(max = 1000, message = "Address must be at most 1000 characters")
    String address,

    @Size(max = 100, message = "City must be at most 100 characters")
    String city,

    @NotBlank(message = "State is required (it decides CGST+SGST vs IGST on documents)")
    @Pattern(regexp = "^[0-9]{2}$", message = "State must be a 2-digit GST state code")
    String stateCode,

    @Pattern(regexp = "^$|^[1-9][0-9]{5}$", message = "PIN code must be 6 digits and cannot start with 0")
    String pinCode,

    @Pattern(regexp = "^$|^(?i)[0-9]{2}[a-z]{5}[0-9]{4}[a-z][1-9a-z]z[0-9a-z]$",
             message = "GST number must be a valid 15-character GSTIN (e.g. 33ABCDE1234F1Z5)")
    String gstNumber,

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[0-9][0-9 \\-]{8,18}[0-9]$", message = "Phone number must contain 10 to 13 digits")
    String phone,

    @Email(message = "Email address is not valid")
    @Size(max = 150, message = "Email must be at most 150 characters")
    String email,

    @Size(max = 255, message = "Website must be at most 255 characters")
    @Pattern(regexp = "^$|^(?i)(https?://)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(/\\S*)?$",
             message = "Website must look like www.example.com or https://example.com")
    String website,

    @Size(max = 500, message = "Logo URL is too long")
    @Pattern(regexp = "^$|^https://.+", message = "Logo must be an uploaded https image")
    String logoUrl,

    @Size(max = 255)
    String logoPublicId
) {}
