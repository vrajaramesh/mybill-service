package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** Assistant knowledge base + rules. updatedAt / updatedBy / aiConfigured / model are ignored on save. */
public record WholesaleAssistantSettingsDto(
    @Size(max = 4000) String orderingProcess,
    @Size(max = 4000) String shippingInfo,
    @Size(max = 4000) String paymentTerms,
    @Size(max = 500) String businessHours,
    @Size(max = 8000) String additionalInfo,
    @NotBlank @Size(max = 500) String unknownReply,
    Boolean shareStockQuantity,
    Boolean allowQuotationDrafts,
    Boolean autoIssueQuotations,
    Boolean aiConfigured,
    String model,
    LocalDateTime updatedAt,
    String updatedBy
) {}
