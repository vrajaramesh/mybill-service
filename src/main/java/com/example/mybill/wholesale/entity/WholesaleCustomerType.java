package com.example.mybill.wholesale.entity;

/**
 * Kinds of wholesale buyer. Stored as the enum name in wholesale_customers.customer_type.
 * RETAILER is the default (see {@link #DEFAULT}).
 */
public enum WholesaleCustomerType {
    WHOLESALE("Wholesale"),
    RETAILER("Retailer");

    public static final WholesaleCustomerType DEFAULT = RETAILER;

    private final String label;

    WholesaleCustomerType(String label) { this.label = label; }

    public String getLabel() { return label; }
}
