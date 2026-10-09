package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

/** Settings → UPI Details. Mandatory: UPI ID; the QR image is optional. */
public record WholesaleUpiDetailsRequest(
    @NotBlank(message = "UPI ID is required")
    @Size(max = 100, message = "UPI ID must be at most 100 characters")
    @Pattern(regexp = "^[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9.]{1,64}$", message = "UPI ID must look like name@bank (e.g. srisa@okhdfcbank)")
    String upiId,

    @Size(max = 500, message = "QR image URL is too long")
    @Pattern(regexp = "^$|^https://.+", message = "QR code must be an uploaded https image")
    String qrImageUrl,

    @Size(max = 255)
    String qrPublicId
) {}
