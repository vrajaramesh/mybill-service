package com.example.mybill.wholesale.dto;

import java.util.List;

/** Dropdown data for customer forms: customer types and GST state codes. */
public record WholesaleCustomerOptions(List<Option> customerTypes, List<Option> states) {
    public record Option(String value, String label) {}
}
