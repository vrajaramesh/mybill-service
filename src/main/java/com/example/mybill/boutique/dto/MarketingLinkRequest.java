package com.example.mybill.boutique.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create / edit a marketing link from the "Ad & post links" page. active = null keeps the current value. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MarketingLinkRequest(
    @NotBlank(message = "source_type is required")
    @Pattern(regexp = "^(AD|IG_MEDIA)$", message = "source_type must be AD or IG_MEDIA")
    String sourceType,

    @NotBlank(message = "source_id is required")
    @Pattern(regexp = "^\\s*[0-9]{1,40}\\s*$", message = "source_id must be numeric (the ad or post ID from Meta)")
    String sourceId,

    /** Exactly one of design_code / collection_code. */
    String designCode,

    @NotBlank(message = "label is required")
    @Size(max = 150, message = "label must be at most 150 characters")
    String label,

    Boolean active,

    /** Optional Instagram post URL (IG_MEDIA), so the agent can resolve the post by its link. */
    @Size(max = 500, message = "permalink must be at most 500 characters")
    String permalink,
    String collectionCode
) {
    @com.fasterxml.jackson.annotation.JsonIgnore
    @jakarta.validation.constraints.AssertTrue(message = "choose a design or a collection")
    public boolean isTargetGiven() {
        boolean design = designCode != null && !designCode.isBlank();
        boolean collection = collectionCode != null && !collectionCode.isBlank();
        return design != collection;
    }
}
