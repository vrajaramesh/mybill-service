package com.example.mybill.wholesale.dto;

import java.util.List;

/**
 * Print-ready business profile for wholesale documents (quotation, credit invoice, sales receipt, debit note).
 * Returned by GET /api/wholesale/business-profile/document. Documents should store a copy at issue time.
 * bank / upi are null when not configured.
 */
public record WholesaleDocumentProfile(
    String firmName,
    List<String> addressLines,
    String stateCode,
    String stateName,
    String gstNumber,
    String phone,
    String email,
    String website,
    String logoUrl,
    Bank bank,
    Upi upi
) {
    public record Bank(String bankName, String accountName, String accountNumber, String ifsc, String branch) {}

    /** payeeName = firm name, for building upi://pay links. */
    public record Upi(String upiId, String payeeName, String qrImageUrl) {}
}
