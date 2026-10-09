package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.WholesaleAssistantChatRequest;
import com.example.mybill.wholesale.dto.WholesaleAssistantSettingsDto;
import com.example.mybill.wholesale.service.WholesaleAssistantService;
import com.example.mybill.wholesale.service.WholesaleAssistantSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Wholesale AI Assistant: test chat (dry run) and knowledge-base settings. See docs/wholesale-ai-assistant.md. */
@RestController
@RequestMapping("/api/wholesale/assistant")
public class WholesaleAssistantController {

    @Autowired private WholesaleAssistantService assistant;
    @Autowired private WholesaleAssistantSettingsService settingsService;
    @Autowired private WholesaleAccess access;

    /** One assistant turn. Dry run: quotations are previewed, never saved; nothing is sent anywhere. */
    @PostMapping("/chat")
    public WholesaleAssistantService.Result chat(@Valid @RequestBody WholesaleAssistantChatRequest request) {
        List<WholesaleAssistantService.Turn> history = request.history() == null ? List.of() : request.history().stream()
            .map(t -> new WholesaleAssistantService.Turn(WholesaleAssistantService.Role.valueOf(t.role()), t.text()))
            .toList();
        return assistant.chat(new WholesaleAssistantService.Request(request.message().trim(), history,
            request.context(), request.customerId(), "test chat", true));
    }

    @GetMapping("/settings")
    public WholesaleAssistantSettingsDto settings() {
        return settingsService.get();
    }

    /** ADMIN only. */
    @PutMapping("/settings")
    public WholesaleAssistantSettingsDto saveSettings(@Valid @RequestBody WholesaleAssistantSettingsDto request,
                                                      HttpServletRequest http) {
        return settingsService.save(request, access.requireAdmin(http));
    }
}
