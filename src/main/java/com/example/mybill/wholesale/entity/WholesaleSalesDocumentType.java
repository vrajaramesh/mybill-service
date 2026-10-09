package com.example.mybill.wholesale.entity;

/**
 * Wholesale sales document types. Labels, printed titles and behaviour (GST class, value/stock effect, reference
 * rules) come from wholesale_document_settings and are copied onto each document; the values here are only
 * fallbacks. Default meaning: CREDIT_NOTE = credit sale (printed "TAX INVOICE (CREDIT SALE)"), DEBIT_NOTE = GST debit
 * note against an earlier receipt / invoice.
 */
public enum WholesaleSalesDocumentType {
    SALES_RECEIPT("Sales Receipt", "SALES RECEIPT"),
    CREDIT_NOTE("Credit Note", "TAX INVOICE (CREDIT SALE)"),
    DEBIT_NOTE("Debit Note", "DEBIT NOTE");

    private final String label;
    private final String printTitle;

    WholesaleSalesDocumentType(String label, String printTitle) {
        this.label = label;
        this.printTitle = printTitle;
    }

    public String getLabel() { return label; }
    public String getPrintTitle() { return printTitle; }
}
