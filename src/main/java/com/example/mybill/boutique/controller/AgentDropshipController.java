package com.example.mybill.boutique.controller;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.service.DropshipIntakeService;
import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp → Dropship intake for srisa-agent, which owns the shop number's webhook (one webhook URL per Meta app):
 * it forwards the owner's messages here. Same guard as the rest of /api/agent/** (Google ID token of the agent's
 * service account, firm in X-Agent-Tenant).
 */
@RestController
@RequestMapping("/api/agent/dropship")
public class AgentDropshipController {

    @Autowired private DropshipIntakeService intake;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;
    @Autowired private com.example.mybill.boutique.service.OwnerNumberService ownerNumbers;
    @Autowired private com.example.mybill.boutique.service.DropshipExtractionService extraction;

    @Autowired private com.example.mybill.boutique.service.DropshipCustomerService customers;

    /** A customer wants to buy a dropship variant: ask the owner(s) Yes / No. {item_code, conversation_id, customer_name, customer_phone, quantity}. */
    @PostMapping("/availability")
    public Map<String, Object> availability(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        int quantity = body.get("quantity") instanceof Number n ? n.intValue() : 1;
        return customers.askAvailability(str(body.get("item_code")), str(body.get("conversation_id")),
            str(body.get("customer_name")), str(body.get("customer_phone")), quantity, Boolean.TRUE.equals(body.get("escalate")));
    }

    /** Someone answered Yes / No. {item_code, quantity, answer y|n, answered_by, customer_name} → {role}. */
    @PostMapping("/availability-answered")
    public Map<String, Object> availabilityAnswered(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        int quantity = body.get("quantity") instanceof Number n ? n.intValue() : 1;
        return customers.answered(str(body.get("item_code")), quantity, "y".equalsIgnoreCase(str(body.get("answer"))),
            str(body.get("answered_by")), str(body.get("customer_name")));
    }

    /** Active suppliers' WhatsApp numbers (the AI must not answer them). */
    @GetMapping("/supplier-numbers")
    public Map<String, List<String>> supplierNumbers() {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return Map.of("numbers", customers.supplierNumbers());
    }

    /** A supplier's message that isn't a Yes / No tap: forwarded to the owner. {from, text}. */
    @PostMapping("/supplier-message")
    public Map<String, Object> supplierMessage(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return customers.supplierMessage(str(body.get("from")), str(body.get("text")));
    }

    /** Stamp a collection's photos again (variant numbers 01, 02 ...). {collection_id} → {restamped}. */
    @PostMapping("/restamp")
    public Map<String, Object> restamp(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return Map.of("restamped", extraction.restamp(Integer.parseInt(str(body.get("collection_id")))));
    }

    /** The owner said the variant is not available: SOLD_OUT. */
    @PostMapping("/items/{code}/sold-out")
    public Map<String, Object> soldOut(@PathVariable String code) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return customers.markSoldOut(code);
    }

    /** Build the DRAFT collection of a post whose supplier is chosen (AI extraction, 20-90 s). Body {batch_id}. */
    @PostMapping("/extract")
    public Map<String, Object> extract(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        Object id = body.get("batch_id");
        if (id == null) throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "batch_id is required", null);
        return extraction.extract(Integer.parseInt(String.valueOf(id)));
    }

    /** Active owner numbers (digits with country code): srisa-agent routes their messages to the intake. */
    @GetMapping("/owner-numbers")
    public Map<String, Object> ownerNumbers() {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return Map.of("numbers", ownerNumbers.activePhones());
    }

    /** {wa_message_id, from, type (text|image), text, media_id, mime_type, timestamp, image_base64}. */
    @PostMapping("/intake")
    public Map<String, Object> intake(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        byte[] image = null;
        if (body.get("image_base64") instanceof String b64 && !b64.isBlank()) {
            try {
                image = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "image_base64 is not valid base64", null);
            }
        }
        Long ts = body.get("timestamp") == null ? null : Long.valueOf(String.valueOf(body.get("timestamp")));
        return intake.intake(new DropshipIntakeService.Incoming(str(body.get("wa_message_id")), str(body.get("from")),
            str(body.get("type")), str(body.get("text")), str(body.get("media_id")), str(body.get("mime_type")), ts, image));
    }

    /** Close quiet batches (srisa-agent calls this from a delayed Cloud Task). Body {force_from?}. */
    @PostMapping("/process-batches")
    public Map<String, Object> processBatches(@RequestBody(required = false) Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return intake.processBatches(body == null ? null : str(body.get("force_from")));
    }

    @Autowired private com.example.mybill.boutique.service.DropshipPublishService publisher;

    /** Post an approved collection on the dropship Instagram account + Meta catalog (20-90 s). Body {collection_id}. */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        int id = Integer.parseInt(String.valueOf(body.get("collection_id")));
        var r = publisher.publish(id, Boolean.TRUE.equals(body.get("repost")));
        intake.tellPublishedTx(id, r);
        return Map.of("account", r.account(), "permalinks", r.permalinks(), "catalog_items", r.catalogItems(),
            "error", r.error() == null ? "" : r.error());
    }

    @Autowired private com.example.mybill.boutique.service.DropshipBoostService boosts;

    /** Boost a posted collection (the owner tapped budget, days, audience). Body {collection_id, daily_budget, days, audience, owner}. */
    @PostMapping("/boost")
    public Map<String, Object> boost(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        String owner = body.get("owner") == null ? null : String.valueOf(body.get("owner")).replaceAll("\\D", "");
        return boosts.boost(Integer.parseInt(String.valueOf(body.get("collection_id"))),
            Integer.parseInt(String.valueOf(body.getOrDefault("daily_budget", 200))),
            Integer.parseInt(String.valueOf(body.get("days"))), String.valueOf(body.get("audience")), owner);
    }

    /** Re-run a FAILED post (owner command RETRY n). Body {batch_id}. */
    @PostMapping("/retry")
    public Map<String, Object> retry(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return intake.retry(Integer.parseInt(String.valueOf(body.get("batch_id"))), null);
    }

    /** The owner tapped a list row / button or sent a command ("done", "NEW SUPPLIER: …"). {from, reply_id, text}. */
    @PostMapping("/owner-reply")
    public Map<String, Object> ownerReply(@RequestBody Map<String, Object> body) {
        schemaInitializer.ensureSchema(TenantContext.getCurrentTenant());
        return intake.ownerReply(str(body.get("from")), str(body.get("reply_id")), str(body.get("text")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
