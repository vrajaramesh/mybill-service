package com.example.mybill.wholesale.controller;

import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.wholesale.service.WholesaleInstagramMessagingService;
import com.example.mybill.wholesale.service.WholesaleInstagramMessagingService.ReceiveResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Meta webhook for the firm's Instagram professional account (Messages field). Public — Meta cannot send a JWT; it is
 * authenticated by the verify token (subscription) and the X-Hub-Signature-256 HMAC of every delivery.
 * See docs/wholesale-instagram-messaging.md.
 */
@RestController
@RequestMapping("/api/public/{firmCode}/wholesale/instagram/webhook")
public class WholesaleInstagramWebhookController {

    @Autowired private FirmRepository firmRepository;
    @Autowired private WholesaleInstagramMessagingService messagingService;

    @GetMapping
    public ResponseEntity<String> verify(@PathVariable String firmCode,
                                         @RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        Optional<Firm> firm = activeFirm(firmCode);
        if (firm.isEmpty() || !"subscribe".equals(mode) || token == null || challenge == null) {
            return ResponseEntity.status(403).body("Forbidden");
        }
        String expected = messagingService.verifyToken(firm.get().getSchemaName());
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).body("Forbidden");
        }
        return ResponseEntity.ok(challenge);
    }

    @PostMapping
    public ResponseEntity<String> receive(@PathVariable String firmCode,
                                          @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                          @RequestBody(required = false) byte[] body) {
        Optional<Firm> firm = activeFirm(firmCode);
        if (firm.isEmpty() || body == null || body.length == 0) return ResponseEntity.ok("OK"); // nothing to retry
        ReceiveResult result = messagingService.receive(firm.get().getSchemaName(), body, signature);
        return switch (result) {
            case ACCEPTED -> ResponseEntity.ok("OK");
            case FORBIDDEN -> ResponseEntity.status(403).body("Invalid signature");
            case FAILED -> ResponseEntity.status(500).body("Retry later");
        };
    }

    private Optional<Firm> activeFirm(String firmCode) {
        return firmRepository.findByFirmCode(firmCode.toLowerCase().trim())
            .filter(f -> Boolean.TRUE.equals(f.getIsActive()) && f.getSchemaName() != null && !f.getSchemaName().isBlank());
    }
}
