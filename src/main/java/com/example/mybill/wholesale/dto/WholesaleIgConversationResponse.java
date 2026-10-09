package com.example.mybill.wholesale.dto;

import java.util.List;

public record WholesaleIgConversationResponse(
    WholesaleIgConversationSummary conversation,
    List<WholesaleIgMessageResponse> messages
) {}
