package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WholesaleCancelRequest(
    @NotBlank(message = "Enter a reason for cancelling")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    String reason
) {}
