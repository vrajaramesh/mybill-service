package com.example.mybill.wholesale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Manual stock correction (counting difference, damage, found stock …). A reason is mandatory. */
public record WholesaleStockAdjustmentRequest(
    @NotNull(message = "Adjustment date is required")
    LocalDate adjustmentDate,

    @NotBlank(message = "Enter the reason for this stock adjustment")
    @Size(max = 500, message = "Reason must be at most 500 characters")
    String reason,

    @NotEmpty(message = "Add at least one product")
    @Size(max = 200, message = "An adjustment can have at most 200 lines")
    List<@Valid @NotNull Line> items
) {
    /** quantityChange: positive adds stock, negative removes it. */
    public record Line(
        @NotNull(message = "Select a product") Integer wholesaleProductId,
        @NotNull(message = "Enter the quantity change")
        @Digits(integer = 9, fraction = 3, message = "Quantity allows at most 3 decimals") BigDecimal quantityChange,
        @Size(max = 255, message = "Notes must be at most 255 characters") String notes
    ) {}
}
