package com.example.mybill.wholesale.dto;

import jakarta.validation.Valid;

/** Issue a draft; optionally record the payment received at the same time. */
public record WholesaleIssueRequest(
    @Valid WholesalePaymentRequest payment
) {}
