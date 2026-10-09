package com.example.mybill.wholesale.entity;

/**
 * Statutory GST document class of a wholesale document type. Configured per type (not hard-coded) because the
 * business name ("Credit Note", "Credit Invoice", ...) may not match the statutory class; stored on each issued
 * document so later configuration or law changes never reinterpret history. Intended for future GST reporting.
 */
public enum WholesaleGstClassification {
    TAX_INVOICE, BILL_OF_SUPPLY, CREDIT_NOTE, DEBIT_NOTE, RECEIPT_VOUCHER, OTHER
}
