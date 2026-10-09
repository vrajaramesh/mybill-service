package com.example.mybill.wholesale.controller;

import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.service.WholesaleInstagramMessagingService;
import com.example.mybill.wholesale.service.WholesaleInstagramSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Wholesale Instagram inbox: conversations, staff replies, human takeover, settings, assistant test. */
@RestController
@RequestMapping("/api/wholesale/instagram")
public class WholesaleInstagramController {

    @Autowired private WholesaleInstagramMessagingService messagingService;
    @Autowired private WholesaleInstagramSettingsService settingsService;
    @Autowired private WholesaleAccess access;
    @Autowired private FirmRepository firmRepository;

    /** ?q=&filter=ALL|ATTENTION|TAKEOVER|UNREAD */
    @GetMapping("/conversations")
    public List<WholesaleIgConversationSummary> conversations(@RequestParam(required = false) String q,
                                                              @RequestParam(required = false) String filter) {
        return messagingService.list(q, filter);
    }

    @GetMapping("/conversations/{id}")
    public WholesaleIgConversationResponse conversation(@PathVariable Integer id) {
        return messagingService.get(id);
    }

    /** Staff reply; switches the conversation to human takeover. Returns the message (status SENT or FAILED). */
    @PostMapping("/conversations/{id}/messages")
    public WholesaleIgMessageResponse send(@PathVariable Integer id, @Valid @RequestBody WholesaleIgSendRequest request,
                                           HttpServletRequest http) {
        return messagingService.sendStaffMessage(id, request.text(), access.username(http));
    }

    @PostMapping("/conversations/{id}/takeover")
    public WholesaleIgConversationSummary takeover(@PathVariable Integer id, HttpServletRequest http) {
        return messagingService.takeover(id, access.username(http));
    }

    /** Re-enables automatic AI replies for the conversation. */
    @PostMapping("/conversations/{id}/release")
    public WholesaleIgConversationSummary release(@PathVariable Integer id) {
        return messagingService.release(id);
    }

    @PostMapping("/conversations/{id}/read")
    public WholesaleIgConversationSummary read(@PathVariable Integer id) {
        return messagingService.markRead(id);
    }

    @PostMapping("/conversations/{id}/resolve")
    public WholesaleIgConversationSummary resolve(@PathVariable Integer id) {
        return messagingService.resolveAttention(id);
    }

    @PutMapping("/conversations/{id}/customer")
    public WholesaleIgConversationSummary linkCustomer(@PathVariable Integer id, @RequestBody WholesaleIgCustomerLinkRequest request) {
        return messagingService.linkCustomer(id, request.customerId());
    }

    @GetMapping("/settings")
    public WholesaleInstagramSettingsResponse settings() {
        return settingsService.get(firmCode());
    }

    /** ADMIN only. */
    @PutMapping("/settings")
    public WholesaleInstagramSettingsResponse saveSettings(@Valid @RequestBody WholesaleInstagramSettingsRequest request,
                                                           HttpServletRequest http) {
        String user = access.requireAdmin(http);
        return settingsService.save(request, user, firmCode());
    }

    private String firmCode() {
        String schema = TenantContext.getCurrentTenant();
        return schema == null ? null : firmRepository.findBySchemaName(schema).map(f -> f.getFirmCode()).orElse(null);
    }
}
