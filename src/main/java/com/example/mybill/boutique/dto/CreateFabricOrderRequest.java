package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** A fabric-only order (no stitching). Same delivery, channel, idempotency and attribution rules as garments. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateFabricOrderRequest(
    @NotNull(message = "customer_id is required")
    Integer customerId,
    @NotNull(message = "product_id is required")
    Integer productId,
    @NotNull(message = "metres is required")
    @DecimalMin(value = "0.5", message = "metres must be at least 0.5")
    @DecimalMax(value = "100", message = "metres must be at most 100")
    BigDecimal metres,
    @NotNull(message = "delivery is required") @Valid
    DeliveryDetails delivery,
    @NotBlank(message = "channel is required")
    @Pattern(regexp = "^(?i)(WHATSAPP|INSTAGRAM|WEB|SHOP)$", message = "channel must be WHATSAPP, INSTAGRAM, WEB or SHOP")
    String channel,
    @NotBlank(message = "idempotency_key is required")
    @Size(max = 100, message = "idempotency_key must be at most 100 characters")
    String idempotencyKey,
    @Pattern(regexp = "^(AD|IG_MEDIA)$", message = "source_type must be AD or IG_MEDIA")
    String sourceType,
    @Pattern(regexp = "^[0-9]{1,40}$", message = "source_id must be numeric")
    String sourceId,
    @Size(max = 500, message = "ctwa_clid must be at most 500 characters")
    String ctwaClid
) {
    @JsonIgnore
    @AssertTrue(message = "source_type and source_id must be given together")
    public boolean isSourceComplete() {
        return (sourceType == null || sourceType.isBlank()) == (sourceId == null || sourceId.isBlank());
    }
}
