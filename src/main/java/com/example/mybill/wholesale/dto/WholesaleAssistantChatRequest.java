package com.example.mybill.wholesale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * In-app assistant chat (test bench). Stateless: the client sends the visible history and the context returned by
 * the previous turn. customerId optionally plays the conversation as that wholesale customer. Nothing is saved.
 */
public record WholesaleAssistantChatRequest(
    @NotBlank @Size(max = 1000) String message,
    @Size(max = 60) List<@Valid @NotNull HistoryTurn> history,
    Map<String, Object> context,
    Integer customerId
) {
    public record HistoryTurn(@NotNull @Pattern(regexp = "CUSTOMER|ASSISTANT|STAFF") String role,
                              @Size(max = 4000) String text) {}
}
