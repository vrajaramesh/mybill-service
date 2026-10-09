package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WholesaleIgSendRequest(@NotBlank @Size(max = 1000) String text) {}
