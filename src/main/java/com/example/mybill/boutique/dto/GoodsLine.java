package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * One line of a goods order: a collection item (item_code, e.g. MCS-1B) or a product (product_id), with
 * quantity = metres for fabric (min 1, steps of 0.25) or pieces for readymade (whole number), and size for readymade.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoodsLine(
    @Size(max = 30) String itemCode,
    Integer productId,
    @NotNull(message = "quantity is required") @DecimalMin(value = "0.25", message = "quantity must be positive")
    BigDecimal quantity,
    @Size(max = 30) String size
) {
    @JsonIgnore
    @AssertTrue(message = "each line needs item_code or product_id")
    public boolean isIdentified() {
        return (itemCode != null && !itemCode.isBlank()) || productId != null;
    }
}
