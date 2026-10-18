package com.example.mybill.boutique.entity;

/**
 * Lifecycle of an agent boutique order. Strictly one-way:
 * DRAFT → PAYMENT_PENDING → PAID → IN_STITCHING → DISPATCHED → DELIVERED, and CANCELLED only before payment.
 * Use {@link #canMoveTo} for every change; webhooks can arrive late or twice and must never move an order back.
 */
public enum BoutiqueOrderStatus {
    DRAFT, PAYMENT_PENDING, PAID, IN_STITCHING, DISPATCHED, DELIVERED, CANCELLED;

    public boolean canMoveTo(BoutiqueOrderStatus next) {
        if (next == null || next == this || this == CANCELLED || this == DELIVERED) return false;
        if (next == CANCELLED) return this == DRAFT || this == PAYMENT_PENDING;
        return next.ordinal() > this.ordinal();
    }

    /** Payment can be requested only before the order is paid (and not when cancelled). */
    public boolean isPayable() {
        return this == DRAFT || this == PAYMENT_PENDING;
    }
}
