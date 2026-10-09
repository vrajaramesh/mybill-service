package com.example.mybill.wholesale.entity;

/**
 * Kind of wholesale stock movement. Sales documents map by their own (configured, frozen) semantics:
 * stock OUT → SALE / CREDIT / DEBIT by document type, stock IN → RETURN. Cancellations and deletions post REVERSAL
 * rows pointing at the original entry.
 */
public enum WholesaleInventoryTransactionType {
    OPENING, PURCHASE, SALE, CREDIT, DEBIT, RETURN, ADJUSTMENT, REVERSAL
}
