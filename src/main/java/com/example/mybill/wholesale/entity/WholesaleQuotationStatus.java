package com.example.mybill.wholesale.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Quotation lifecycle.
 * <pre>
 * DRAFT ──issue──▶ ISSUED ──▶ ACCEPTED ──▶ CONVERTED (by converting to a Sales Receipt / Credit Note)
 *   │                │  ├──────────────────▶ CONVERTED (converting an issued quotation implies acceptance)
 *   │                │  ├──▶ REJECTED
 *   │                │  └──▶ EXPIRED   (automatically, after valid_until)
 *   └──▶ CANCELLED ◀─┴── (ISSUED / ACCEPTED can also be cancelled)
 * </pre>
 * Only DRAFT quotations can be edited or deleted; anything else is changed by duplicating it.
 * CONVERTED → ACCEPTED is a system-only step, taken when every document created from the quotation is cancelled
 * (see {@link #canRevertConversion()}); users cannot request it.
 */
public enum WholesaleQuotationStatus {
    DRAFT, ISSUED, ACCEPTED, REJECTED, EXPIRED, CONVERTED, CANCELLED;

    public Set<WholesaleQuotationStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> EnumSet.of(ISSUED, CANCELLED);
            case ISSUED -> EnumSet.of(ACCEPTED, REJECTED, EXPIRED, CONVERTED, CANCELLED);
            case ACCEPTED -> EnumSet.of(CONVERTED, CANCELLED);
            case REJECTED, EXPIRED, CONVERTED, CANCELLED -> EnumSet.noneOf(WholesaleQuotationStatus.class);
        };
    }

    public boolean canMoveTo(WholesaleQuotationStatus next) {
        return allowedNext().contains(next);
    }

    /** Statuses a quotation can be converted from (CONVERTED only with an explicit "allow duplicate"). */
    public boolean isConvertible() {
        return this == ISSUED || this == ACCEPTED;
    }

    /** System transition when all conversions of a quotation have been cancelled. */
    public boolean canRevertConversion() {
        return this == CONVERTED;
    }

    public boolean isEditable() {
        return this == DRAFT;
    }
}
