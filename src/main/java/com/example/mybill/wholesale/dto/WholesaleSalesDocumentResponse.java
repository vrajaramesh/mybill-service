package com.example.mybill.wholesale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Full Sales Receipt / Credit Note. customer and totals use the same shapes as quotations. */
public record WholesaleSalesDocumentResponse(
    Integer salesDocumentId,
    String docType,
    String docTypeLabel,
    String printTitle,
    String documentNumber,
    LocalDate documentDate,
    String status,
    Integer sourceQuotationId,
    String sourceQuotationNumber,
    /** Semantics this document was created/issued under (from the document-type configuration). */
    Semantics semantics,
    Reference reference,
    String reason,
    WholesaleDocumentProfile firm,
    WholesaleQuotationResponse.Customer customer,
    String placeOfSupplyStateCode,
    Boolean interstate,
    List<Item> items,
    WholesaleQuotationResponse.Totals totals,
    Payment payment,
    String notes,
    String terms,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime issuedAt,
    String issuedBy,
    LocalDateTime convertedAt,
    String convertedBy,
    LocalDateTime cancelledAt,
    String cancelledBy,
    String cancelReason,
    List<PaymentRecord> payments,
    List<AuditEvent> auditTrail,
    List<String> allowedActions,
    List<String> warnings
) {
    public record Semantics(String gstClassification, String valueEffect, String stockEffect) {}

    /** docType = QUOTATION, SALES_RECEIPT, CREDIT_NOTE, DEBIT_NOTE or EXTERNAL; documentId / quotationId when internal. */
    public record Reference(String docType, Integer documentId, Integer quotationId, String number, LocalDate date) {}

    public record AuditEvent(String eventType, LocalDateTime eventAt, String eventBy, String details) {}

    /** One payment; VOIDED payments are kept for the audit trail but do not count towards amountPaid. */
    public record PaymentRecord(Integer paymentId, LocalDate paymentDate, BigDecimal amount, String paymentMode,
                                String reference, String notes, String status, LocalDateTime createdAt, String createdBy,
                                LocalDateTime voidedAt, String voidedBy, String voidReason) {}

    public record Item(Integer salesDocumentItemId, Integer lineNo, Integer sourceQuotationItemId, Integer wholesaleProductId,
                       String itemName, String productCode, String hsnCode, String unit, String description,
                       BigDecimal quantity, BigDecimal rate, String rateSource, BigDecimal discountPct,
                       BigDecimal grossAmount, BigDecimal discountAmount, BigDecimal taxableAmount, BigDecimal gstPct,
                       BigDecimal cgstAmount, BigDecimal sgstAmount, BigDecimal igstAmount, BigDecimal gstAmount,
                       BigDecimal totalAmount) {}

    public record Payment(String paymentMode, String paymentReference, BigDecimal amountPaid, String paymentStatus,
                          LocalDate dueDate, BigDecimal balanceDue) {}
}
