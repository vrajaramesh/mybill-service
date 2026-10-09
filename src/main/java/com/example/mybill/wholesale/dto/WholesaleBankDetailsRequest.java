package com.example.mybill.wholesale.dto;

import jakarta.validation.constraints.*;

/** Settings → Bank Details. Mandatory: bank name, account name, account number, IFSC. */
public record WholesaleBankDetailsRequest(
    @NotBlank(message = "Bank name is required")
    @Size(max = 100, message = "Bank name must be at most 100 characters")
    String bankName,

    @NotBlank(message = "Account name is required")
    @Size(max = 150, message = "Account name must be at most 150 characters")
    String accountName,

    @NotBlank(message = "Account number is required")
    @Pattern(regexp = "^[0-9]{9,18}$", message = "Account number must be 9 to 18 digits")
    String accountNumber,

    @NotBlank(message = "IFSC is required")
    @Pattern(regexp = "^(?i)[a-z]{4}0[a-z0-9]{6}$", message = "IFSC must be 11 characters like SBIN0001234 (5th character is 0)")
    String ifsc,

    @Size(max = 150, message = "Branch must be at most 150 characters")
    String branch
) {}
