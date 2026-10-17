package com.example.mybill.boutique.entity;

/** Lifecycle of an agent boutique order. Orders are created as DRAFT; payment and fulfilment come later. */
public enum BoutiqueOrderStatus {
    DRAFT, PAID, IN_STITCHING, DISPATCHED, DELIVERED, CANCELLED
}
