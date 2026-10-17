package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QuoteRequest(
    @NotBlank(message = "design_code is required")
    String designCode,

    @NotBlank(message = "size_code is required")
    String sizeCode,

    @Size(max = 10, message = "At most 10 options can be selected")
    List<@NotNull(message = "option_ids must not contain null") Integer> optionIds,

    @Min(value = 1, message = "qty must be at least 1")
    @Max(value = 10, message = "qty must be at most 10")
    Integer qty,

    @Pattern(regexp = "^$|^[1-9][0-9]{5}$", message = "pincode must be 6 digits and cannot start with 0")
    String pincode
) {}
