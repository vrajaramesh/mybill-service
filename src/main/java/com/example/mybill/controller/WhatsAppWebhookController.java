package com.example.mybill.controller;

import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/public/{firmCode}/whatsapp")
public class WhatsAppWebhookController {

    @Autowired private FirmRepository firmRepository;
    @Autowired private ChatService chatService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${whatsapp.api.url}")
    private String whatsappApiUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Meta webhook verification — returns hub.challenge if verify token matches. */
    @GetMapping("/webhook")
    public ResponseEntity<?> verify(
            @PathVariable String firmCode,
            @RequestParam("hub.mode")         String mode,
            @RequestParam("hub.challenge")    String challenge,
            @RequestParam("hub.verify_token") String verifyToken) {

        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty()) return ResponseEntity.status(403).body("Forbidden");

        String storedToken = getSetting(firmOpt.get().getSchemaName(), "whatsapp_verify_token");
        if ("subscribe".equals(mode) && verifyToken.equals(storedToken)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    /** Incoming WhatsApp message — process and reply via Cloud API. */
    @PostMapping("/webhook")
    public ResponseEntity<?> incoming(
            @PathVariable String firmCode,
            @RequestBody String rawBody) {

        // Always return 200 so Meta does not retry
        Optional<Firm> firmOpt = firmRepository.findByFirmCode(firmCode.toLowerCase().trim());
        if (firmOpt.isEmpty() || !Boolean.TRUE.equals(firmOpt.get().getIsActive())) {
            return ResponseEntity.ok("OK");
        }

        Firm firm = firmOpt.get();
        String schema = firm.getSchemaName();

        try {
            JsonNode root = mapper.readTree(rawBody);
            JsonNode msgArr = root.path("entry").path(0)
                .path("changes").path(0)
                .path("value").path("messages");

            if (msgArr.isMissingNode() || !msgArr.isArray() || msgArr.isEmpty()) {
                return ResponseEntity.ok("OK");
            }

            JsonNode msg = msgArr.get(0);
            if (!"text".equals(msg.path("type").asText())) {
                return ResponseEntity.ok("OK"); // ignore non-text for now
            }

            String from     = msg.path("from").asText();
            String userText = msg.path("text").path("body").asText();
            String sessionId = "wa_" + from;

            String ecomUrl = getSetting(schema, "ecom_url");

            ChatService.ChatResponse response = chatService.chat(
                schema, firm.getFirmName(), sessionId, userText, ecomUrl, null, null, "whatsapp");

            String phoneNumberId = getSetting(schema, "whatsapp_phone_number_id");
            String accessToken   = getSetting(schema, "whatsapp_access_token");

            if (phoneNumberId != null && !phoneNumberId.isBlank()
                    && accessToken != null && !accessToken.isBlank()) {
                sendMessage(phoneNumberId, accessToken, from, stripHtml(response.reply));
            }

        } catch (Exception e) {
            System.err.println("[WhatsApp] Error: " + e.getMessage());
        }

        return ResponseEntity.ok("OK");
    }

    private void sendMessage(String phoneNumberId, String accessToken,
                              String to, String text) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", text)
            ));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(whatsappApiUrl + "/" + phoneNumberId + "/messages"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            System.err.println("[WhatsApp] Sent to " + to + " → HTTP " + resp.statusCode());
        } catch (Exception e) {
            System.err.println("[WhatsApp] Send failed: " + e.getMessage());
        }
    }

    /** Strip HTML tags so the reply reads cleanly in WhatsApp. */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html
            .replaceAll("<br\\s*/?>", "\n")
            .replaceAll("<[^>]+>", "")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"")
            .trim();
    }

    private String getSetting(String schema, String key) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT value FROM \"" + schema + "\".firm_settings WHERE key = ?", key);
            if (!rows.isEmpty()) {
                Object val = rows.get(0).get("value");
                return val != null ? val.toString() : null;
            }
        } catch (Exception e) {
            System.err.println("[WhatsApp] getSetting error: " + e.getMessage());
        }
        return null;
    }
}
