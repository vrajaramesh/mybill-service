package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Numbering and defaults for one document type. nextNumber (optional) sets the next sequence for the
 * current period, e.g. to continue from a paper book; it must be greater than the last number used.
 */
public record WholesaleDocumentSettingsRequest(
    @NotBlank(message = "Prefix is required")
    @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$", message = "Prefix may use letters, digits and '-' (max 20)")
    String prefix,

    @NotNull(message = "Separator is required")
    @Pattern(regexp = "^(/|-|)$", message = "Separator must be '/', '-' or empty")
    String separator,

    @NotNull Boolean includeFinancialYear,

    @NotNull Boolean resetEachFinancialYear,

    @NotNull
    @Min(value = 1, message = "Number padding must be 1 to 8 digits")
    @Max(value = 8, message = "Number padding must be 1 to 8 digits")
    Integer numberPadding,

    @NotNull
    @Min(value = 1, message = "Default validity must be 1 to 365 days")
    @Max(value = 365, message = "Default validity must be 1 to 365 days")
    Integer defaultValidityDays,

    @Size(max = 4000, message = "Default terms must be at most 4000 characters")
    String defaultTerms,

    @Min(value = 0, message = "Default due days must be 0 to 365")
    @Max(value = 365, message = "Default due days must be 0 to 365")
    Integer defaultDueDays,

    @Min(value = 1, message = "Next number must be at least 1")
    Integer nextNumber,

    // ── Configurable semantics (sales documents only; ignored for QUOTATION). Empty = keep current value. ──

    @Size(max = 60, message = "Label must be at most 60 characters")
    String displayLabel,

    @Size(max = 80, message = "Printed title must be at most 80 characters")
    String printTitle,

    @Pattern(regexp = "^$|TAX_INVOICE|BILL_OF_SUPPLY|CREDIT_NOTE|DEBIT_NOTE|RECEIPT_VOUCHER|OTHER", message = "Unknown GST classification")
    String gstClassification,

    @Pattern(regexp = "^$|CHARGE|CREDIT", message = "Value effect must be CHARGE or CREDIT")
    String valueEffect,

    @Pattern(regexp = "^$|OUT|IN|NONE", message = "Stock effect must be OUT, IN or NONE")
    String stockEffect,

    @Pattern(regexp = "^$|NONE|OPTIONAL|REQUIRED", message = "Reference mode must be NONE, OPTIONAL or REQUIRED")
    String referenceMode,

    List<@Pattern(regexp = "QUOTATION|SALES_RECEIPT|CREDIT_NOTE|DEBIT_NOTE", message = "Unknown reference document type") String> allowedReferenceTypes,

    Boolean reasonRequired
) {}
