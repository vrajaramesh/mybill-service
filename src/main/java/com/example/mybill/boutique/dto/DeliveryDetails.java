package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeliveryDetails(
    @NotBlank(message = "delivery.name is required") @Size(max = 100, message = "delivery.name must be at most 100 characters")
    String name,

    @NotBlank(message = "delivery.phone is required")
    @Pattern(regexp = "^\\+?[0-9][0-9 \\-]{8,18}[0-9]$", message = "delivery.phone must contain 10 to 13 digits")
    String phone,

    @NotBlank(message = "delivery.address_line1 is required") @Size(max = 200, message = "delivery.address_line1 is too long")
    String addressLine1,

    @Size(max = 200, message = "delivery.address_line2 is too long")
    String addressLine2,

    @NotBlank(message = "delivery.city is required") @Size(max = 100, message = "delivery.city is too long")
    String city,

    @NotBlank(message = "delivery.state is required") @Size(max = 100, message = "delivery.state is too long")
    String state,

    @NotBlank(message = "delivery.pincode is required")
    @Pattern(regexp = "^[1-9][0-9]{5}$", message = "delivery.pincode must be 6 digits and cannot start with 0")
    String pincode
) {}
