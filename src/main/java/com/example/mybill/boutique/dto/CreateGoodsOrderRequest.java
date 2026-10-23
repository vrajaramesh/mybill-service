package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/** A goods order (fabric / readymade lines). Same delivery, channel, idempotency and attribution rules as garments. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateGoodsOrderRequest(
    @NotNull(message = "customer_id is required") Integer customerId,
    @NotEmpty(message = "lines are required") @Size(max = 20, message = "at most 20 lines") List<@Valid GoodsLine> lines,
    @NotNull(message = "delivery is required") @Valid DeliveryDetails delivery,
    @NotBlank(message = "channel is required")
    @Pattern(regexp = "^(?i)(WHATSAPP|INSTAGRAM|WEB|SHOP)$", message = "channel must be WHATSAPP, INSTAGRAM, WEB or SHOP")
    String channel,
    @NotBlank(message = "idempotency_key is required") @Size(max = 100) String idempotencyKey,
    @Pattern(regexp = "^(AD|IG_MEDIA)$", message = "source_type must be AD or IG_MEDIA") String sourceType,
    @Pattern(regexp = "^[0-9]{1,40}$", message = "source_id must be numeric") String sourceId,
    @Size(max = 500) String ctwaClid
) {
    @JsonIgnore
    @AssertTrue(message = "source_type and source_id must be given together")
    public boolean isSourceComplete() {
        return (sourceType == null || sourceType.isBlank()) == (sourceId == null || sourceId.isBlank());
    }
}
