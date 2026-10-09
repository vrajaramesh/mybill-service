package com.example.mybill.wholesale.entity;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Wholesale Sales Receipt / Credit Note lifecycle.
 * <pre>
 * DRAFT ──issue──▶ ISSUED ──payment──▶ PARTIALLY_PAID ──payment──▶ PAID
 *   │                │  └────────────── full payment ──────────────▶ │
 *   └──▶ CANCELLED ◀─┴────────────────────────┴───────────────────────┘
 * </pre>
 * After issue, ISSUED / PARTIALLY_PAID / PAID follow the recorded payments ({@link #forPayment}); voiding a payment
 * can move a document back (e.g. PAID → PARTIALLY_PAID). Only DRAFT is editable/deletable; CANCELLED is final.
 */
public enum WholesaleSalesDocumentStatus {
    DRAFT, ISSUED, PARTIALLY_PAID, PAID, CANCELLED;

    public Set<WholesaleSalesDocumentStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> EnumSet.of(ISSUED, PARTIALLY_PAID, PAID, CANCELLED);
            case ISSUED -> EnumSet.of(PARTIALLY_PAID, PAID, CANCELLED);
            case PARTIALLY_PAID -> EnumSet.of(ISSUED, PARTIALLY_PAID, PAID, CANCELLED);
            case PAID -> EnumSet.of(ISSUED, PARTIALLY_PAID, CANCELLED);
            case CANCELLED -> EnumSet.noneOf(WholesaleSalesDocumentStatus.class);
        };
    }

    public boolean canMoveTo(WholesaleSalesDocumentStatus next) {
        return allowedNext().contains(next);
    }

    /** Issued (numbered, stock deducted) and not cancelled. */
    public boolean isActive() {
        return this == ISSUED || this == PARTIALLY_PAID || this == PAID;
    }

    public boolean isEditable() {
        return this == DRAFT;
    }

    /** Payments can be added while something is still due. */
    public boolean acceptsPayments() {
        return this == ISSUED || this == PARTIALLY_PAID;
    }

    /** Status of an issued document for the given amount paid against its grand total. */
    public static WholesaleSalesDocumentStatus forPayment(BigDecimal paid, BigDecimal total) {
        if (paid.signum() < 0 || paid.compareTo(total) > 0) {
            throw new IllegalArgumentException("Paid amount must be between 0 and the grand total");
        }
        if (paid.compareTo(total) == 0) return PAID;
        return paid.signum() == 0 ? ISSUED : PARTIALLY_PAID;
    }

    /** Legacy payment_status column kept in step with the lifecycle. */
    public static String paymentStatusFor(BigDecimal paid, BigDecimal total) {
        if (paid.compareTo(total) >= 0) return "PAID";
        return paid.signum() == 0 ? "UNPAID" : "PARTIAL";
    }
}
