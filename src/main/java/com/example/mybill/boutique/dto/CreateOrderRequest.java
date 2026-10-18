package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateOrderRequest(
    @NotNull(message = "customer_id is required")
    Integer customerId,

    @NotBlank(message = "design_code is required")
    String designCode,

    @NotBlank(message = "size_code is required")
    String sizeCode,

    @Size(max = 10, message = "At most 10 options can be selected")
    List<@NotNull(message = "option_ids must not contain null") Integer> optionIds,

    @NotNull(message = "qty is required")
    @Min(value = 1, message = "qty must be at least 1")
    @Max(value = 10, message = "qty must be at most 10")
    Integer qty,

    @NotNull(message = "delivery is required") @Valid
    DeliveryDetails delivery,

    /** Optional: measurements given in this conversation; else the customer's latest saved profile is used. */
    @Valid
    OrderMeasurements measurements,

    @NotBlank(message = "channel is required")
    @Pattern(regexp = "^(?i)(WHATSAPP|INSTAGRAM|WEB|SHOP)$", message = "channel must be WHATSAPP, INSTAGRAM, WEB or SHOP")
    String channel,

    /** Unique per order attempt (e.g. the agent's conversation id + a counter). Retries must reuse it. */
    @NotBlank(message = "idempotency_key is required")
    @Size(max = 100, message = "idempotency_key must be at most 100 characters")
    String idempotencyKey,

    /** Optional marketing attribution, set when the conversation started from a Click-to-WhatsApp ad or IG post. */
    @Pattern(regexp = "^(AD|IG_MEDIA)$", message = "source_type must be AD or IG_MEDIA")
    String sourceType,

    @Pattern(regexp = "^[0-9]{1,40}$", message = "source_id must be numeric")
    String sourceId,

    @Size(max = 500, message = "ctwa_clid must be at most 500 characters")
    String ctwaClid
) {
    /** source_type and source_id come as a pair. */
    @JsonIgnore
    @AssertTrue(message = "source_type and source_id must be given together")
    public boolean isSourceComplete() {
        return (sourceType == null || sourceType.isBlank()) == (sourceId == null || sourceId.isBlank());
    }
}
