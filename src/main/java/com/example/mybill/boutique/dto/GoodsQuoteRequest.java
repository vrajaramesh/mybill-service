package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Price for a goods cart (fabric metres / readymade sizes), optionally with delivery to a pincode. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoodsQuoteRequest(
    @NotEmpty(message = "lines are required") @Size(max = 20, message = "at most 20 lines") List<@Valid GoodsLine> lines,
    @Pattern(regexp = "^$|^[1-9][0-9]{5}$", message = "pincode must be 6 digits and cannot start with 0")
    String pincode
) {}
