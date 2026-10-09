package com.example.mybill.wholesale.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Settings plus where numbering stands now (currentPeriod / lastNumber) and what the next number will look like. */
public record WholesaleDocumentSettingsResponse(
    String docType,
    String prefix,
    String separator,
    Boolean includeFinancialYear,
    Boolean resetEachFinancialYear,
    Integer numberPadding,
    Integer defaultValidityDays,
    String defaultTerms,
    Integer defaultDueDays,
    String currentPeriod,
    Integer lastNumber,
    String nextNumberPreview,
    LocalDateTime updatedAt,
    String updatedBy,
    String displayLabel,
    String printTitle,
    String gstClassification,
    String valueEffect,
    String stockEffect,
    String referenceMode,
    List<String> allowedReferenceTypes,
    Boolean reasonRequired
) {}
