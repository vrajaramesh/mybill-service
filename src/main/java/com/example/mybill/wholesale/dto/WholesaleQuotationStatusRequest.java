package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Manual status change: ACCEPTED, REJECTED or CANCELLED (ISSUED via /issue; EXPIRED is automatic). */
public record WholesaleQuotationStatusRequest(
    @NotBlank(message = "Status is required")
    @Pattern(regexp = "ACCEPTED|REJECTED|CANCELLED", message = "Status must be ACCEPTED, REJECTED or CANCELLED")
    String status,

    @Size(max = 255, message = "Reason must be at most 255 characters")
    String reason
) {}
