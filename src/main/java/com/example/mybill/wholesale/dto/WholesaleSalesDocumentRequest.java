package com.example.mybill.wholesale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Create / update (DRAFT only) / preview any wholesale sales document: SALES_RECEIPT, CREDIT_NOTE or DEBIT_NOTE.
 * Reference (per the document type's configuration): referenceDocumentId (an earlier wholesale document),
 * referenceQuotationId, or an external invoice (externalReferenceNumber + externalReferenceDate) — only one.
 * Lines are priced like quotations (rate empty → slab on the document date) and the rate used is stored.
 */
public record WholesaleSalesDocumentRequest(
    @NotBlank(message = "Document type is required")
    @Pattern(regexp = "SALES_RECEIPT|CREDIT_NOTE|DEBIT_NOTE", message = "Document type must be SALES_RECEIPT, CREDIT_NOTE or DEBIT_NOTE")
    String docType,

    @NotNull(message = "Select a customer")
    Integer customerId,

    @NotNull(message = "Document date is required")
    LocalDate documentDate,

    Integer referenceDocumentId,

    Integer referenceQuotationId,

    @Size(max = 60, message = "External reference number must be at most 60 characters")
    String externalReferenceNumber,

    LocalDate externalReferenceDate,

    @Size(max = 500, message = "Reason must be at most 500 characters")
    String reason,

    /** Payable documents other than Sales Receipts (default: document date + configured due days). */
    LocalDate dueDate,

    @Size(max = 60, message = "Other charges label must be at most 60 characters")
    String otherChargesLabel,

    @DecimalMin(value = "0", message = "Other charges cannot be negative")
    @Digits(integer = 12, fraction = 2, message = "Other charges allow at most 2 decimals")
    BigDecimal otherChargesAmount,

    @DecimalMin(value = "0", message = "Other charges GST % cannot be negative")
    @DecimalMax(value = "100", message = "Other charges GST % cannot exceed 100")
    BigDecimal otherChargesGstPct,

    String notes,

    String terms,

    @NotEmpty(message = "Add at least one item")
    @Size(max = 200, message = "A document can have at most 200 items")
    List<@Valid @NotNull WholesaleQuotationItemRequest> items
) {
    /** Phase 7 Sales Receipt request mapped to the general request. */
    public static WholesaleSalesDocumentRequest fromReceipt(WholesaleSalesReceiptRequest r) {
        return new WholesaleSalesDocumentRequest("SALES_RECEIPT", r.customerId(), r.receiptDate(), null, null, null, null,
            null, null, r.otherChargesLabel(), r.otherChargesAmount(), r.otherChargesGstPct(), r.notes(), r.terms(), r.items());
    }
}
