package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.AgentOrderService;
import com.example.mybill.boutique.service.AgentStaffClient;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * mybill-ui "Customer chats": the WhatsApp/Instagram conversations the AI handed to the team. Normal app login; the
 * conversations live in srisa-agent, which serves one firm (agent.tenant-schema), so other firms see an empty inbox.
 */
@RestController
@RequestMapping("/api/boutique-chats")
public class StaffChatController {

    @Autowired private AgentStaffClient agent;
    @Autowired private AgentOrderService orderService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;

    @Value("${agent.tenant-schema:firm_srisa}") private String agentTenant;

    @GetMapping
    public JsonNode list(@RequestParam(defaultValue = "waiting") String status) {
        if (!agentFirm()) return objectMapper.createArrayNode();
        if (!status.equals("waiting") && !status.equals("all")) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "status must be waiting or all", null);
        }
        return agent.get("/staff/conversations?status=" + status + "&limit=100");
    }

    @GetMapping("/count")
    public JsonNode count() {
        if (!agentFirm()) return objectMapper.createObjectNode().put("waiting", 0);
        return agent.get("/staff/conversations/count");
    }

    /** The conversation, its messages, and the customer's orders from mybill. */
    @GetMapping("/{id}")
    public JsonNode get(@PathVariable String id) {
        requireAgentFirm();
        ObjectNode conversation = (ObjectNode) agent.get("/staff/conversations/" + uuid(id));
        JsonNode customerId = conversation.get("mybill_customer_id");
        conversation.set("orders", customerId == null || customerId.isNull() ? objectMapper.createArrayNode()
            : objectMapper.valueToTree(orderService.listForCustomer(customerId.asInt(), 10)));
        return conversation;
    }

    @PostMapping("/{id}/reply")
    public JsonNode reply(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireAgentFirm();
        String text = body.get("text") instanceof String s ? s : "";
        String imageUrl = body.get("image_url") instanceof String s && !s.isBlank() ? s : null;
        if (text.isBlank() && imageUrl == null) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "text or image_url is required", null);
        }
        if (imageUrl != null && !imageUrl.startsWith("https://")) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "image_url must be https", null);
        }
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("text", text);
        if (imageUrl != null) payload.put("image_url", imageUrl);
        String staff = staffName(request);
        if (staff != null) payload.put("staff_name", staff);
        return agent.post("/staff/conversations/" + uuid(id) + "/reply", payload);
    }

    /** A customer's photo/video/document from the chat (downloaded from WhatsApp via the agent). */
    @GetMapping("/{id}/media/{messageId}")
    public ResponseEntity<byte[]> media(@PathVariable String id, @PathVariable String messageId) {
        requireAgentFirm();
        uuid(id);
        AgentStaffClient.Media media = agent.getMedia("/staff/media/" + uuid(messageId));
        if (media == null) throw AgentApiException.notFound("MEDIA_GONE", "This file is no longer available on WhatsApp");
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(media.contentType()))
            .header("Cache-Control", "private, max-age=86400")
            .body(media.bytes());
    }

    @PostMapping("/{id}/resolve")
    public JsonNode resolve(@PathVariable String id) {
        requireAgentFirm();
        return agent.post("/staff/conversations/" + uuid(id) + "/resolve", Map.of());
    }

    @PostMapping("/{id}/keep")
    public JsonNode keep(@PathVariable String id) {
        requireAgentFirm();
        return agent.post("/staff/conversations/" + uuid(id) + "/keep", Map.of());
    }

    /** Remove from Customer chats until the customer writes again (no AI reminders meanwhile). */
    @PostMapping("/{id}/dismiss")
    public JsonNode dismiss(@PathVariable String id) {
        requireAgentFirm();
        return agent.post("/staff/conversations/" + uuid(id) + "/dismiss", Map.of());
    }

    private boolean agentFirm() {
        return agentTenant.equals(TenantContext.getCurrentTenant());
    }

    private void requireAgentFirm() {
        if (!agentFirm()) throw AgentApiException.notFound("CHAT_NOT_FOUND", "No customer chats for this firm");
    }

    private static String uuid(String id) {
        try {
            return java.util.UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            throw AgentApiException.notFound("CHAT_NOT_FOUND", "No chat " + id);
        }
    }

    private String staffName(HttpServletRequest request) {
        try {
            String header = request.getHeader("Authorization");
            return jwtUtil.extractClaims(header.substring("Bearer ".length())).getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
