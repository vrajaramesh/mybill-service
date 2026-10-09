package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.entity.WholesaleAssistantSettings;
import com.example.mybill.wholesale.entity.WholesaleBusinessProfile;
import com.example.mybill.wholesale.repository.WholesaleBusinessProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controlled Wholesale AI Assistant (channel independent: Instagram, the in-app assistant, …).
 *
 * <p>The model only understands the customer and decides which backend tool to call
 * ({@link WholesaleAssistantTools}); it never touches the database. It must finish with {@code send_reply}, stating
 * whether it could answer. Before a reply is accepted, every number in it must appear in a tool result, the
 * conversation context or something the customer wrote; otherwise the model gets one chance to correct itself and then
 * the configured "I don't have that information" reply is used. Unanswerable questions get the same reply and are
 * flagged for staff.
 *
 * <p>Conversation history: the caller passes the recent turns; this class keeps the newest ones within a size budget.
 * The structured context (current product, quantity, last quotation) survives beyond that window, so customers never
 * have to repeat the product ("100 meters?" → "Price?").
 */
@Service
public class WholesaleAssistantService {

    public record Turn(Role role, String text) {}

    public enum Role { CUSTOMER, ASSISTANT, STAFF }

    /** Who is asking and where: customerId = mapped wholesale customer (may be null); dryRun = never save anything. */
    public record Request(String message, List<Turn> history, Map<String, Object> context, Integer customerId,
                          String channel, boolean dryRun) {}

    /**
     * @param replySource AI (verified model reply), UNKNOWN (configured unknown reply), ERROR (AI unavailable)
     * @param toolCalls   every tool call with input and result (the business data the reply is based on)
     * @param quotation   quotation saved during this turn (id, status, number) or null
     */
    public record Result(String reply, String replySource, String intent, boolean answered, String confidence,
                         boolean needsHuman, String attentionReason, List<Map<String, Object>> toolCalls,
                         Map<String, Object> context, Map<String, Object> quotation) {}

    private static final int MAX_STEPS = 8;
    private static final int MAX_HISTORY_TURNS = 16;
    private static final int MAX_HISTORY_CHARS = 8000;
    private static final int MAX_REPLY = 950;
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    /** List numbering ("1." at a line start, "2)" anywhere) is formatting, not a business fact. */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d{1,2}[.)](?=\\s)|(?<![\\d.,₹])\\b\\d{1,2}\\)(?=\\s)");

    @Autowired private WholesaleLlmClient llm;
    @Autowired private WholesaleAssistantTools tools;
    @Autowired private WholesaleAssistantSettingsService settingsService;
    @Autowired private WholesaleBusinessProfileRepository profileRepository;

    private final ObjectMapper json = new ObjectMapper();

    public Result chat(Request request) {
        WholesaleAssistantSettings settings = settingsService.current();
        String unknown = settings.getUnknownReply();
        String firmName = profileRepository.findById(WholesaleBusinessProfile.SINGLETON_ID).map(WholesaleBusinessProfile::getFirmName)
            .filter(n -> n != null && !n.isBlank()).orElse("our wholesale business");
        WholesaleAssistantTools.Session session = new WholesaleAssistantTools.Session(request.customerId(), request.dryRun(),
            "AI assistant (" + request.channel() + ")", settings, request.context());

        ArrayNode messages = conversation(request.history(), request.message());
        String system = systemPrompt(firmName, settings, session.context, request.customerId() != null, request.dryRun());
        List<WholesaleLlmClient.Tool> definitions = tools.definitions();

        JsonNode finalReply = null;
        boolean corrected = false;
        boolean gaveUp = false;
        boolean nudged = false;
        String rejected = null;
        try {
            for (int step = 0; step < MAX_STEPS && finalReply == null; step++) {
                JsonNode response = llm.create(system, messages, definitions, 1500);
                JsonNode content = response.path("content");
                messages.addObject().put("role", "assistant").set("content", content);

                ArrayNode results = json.createArrayNode();
                boolean usedTool = false;
                for (JsonNode block : content) {
                    if (!"tool_use".equals(block.path("type").asText())) continue;
                    usedTool = true;
                    String name = block.path("name").asText();
                    String id = block.path("id").asText();
                    if (WholesaleAssistantTools.SEND_REPLY.equals(name)) {
                        String problem = verify(block.path("input").path("reply").asText(""), session, request);
                        if (problem == null) {
                            finalReply = block.path("input");
                        } else if (!corrected) {
                            corrected = true;
                            rejected = problem;
                            results.add(toolResult(id, "REJECTED: " + problem + ". Only use numbers that appear in tool "
                                + "results or the customer's messages; call a tool if you need a figure, or say you do not "
                                + "have that information. Then call send_reply again.", true));
                        } else {
                            rejected = problem;
                            gaveUp = true;
                        }
                    } else {
                        Map<String, Object> out = tools.execute(name, block.path("input"), session);
                        results.add(toolResult(id, json.writeValueAsString(out), out.containsKey("error")));
                    }
                }
                if (finalReply != null || gaveUp) break; // done, or the corrected reply was rejected again
                if (!usedTool) {
                    // answered in plain text: ask once for send_reply so the reply is checked and classified
                    if (nudged) break;
                    nudged = true;
                    messages.addObject().put("role", "user").put("content",
                        "(System) Deliver your answer by calling the send_reply tool, using only tool results.");
                    continue;
                }
                messages.addObject().put("role", "user").set("content", results);
            }
        } catch (Exception e) {
            System.err.println("[WholesaleAssistant] " + e.getMessage());
            session.attention.add("AI unavailable: " + e.getMessage());
            return result(unknown, "ERROR", "OTHER", false, "low", session);
        }

        if (finalReply == null) {
            session.attention.add(rejected != null ? "AI reply rejected (" + rejected + ")" : "AI did not produce a reply");
            return result(unknown, "UNKNOWN", "OTHER", false, "low", session);
        }

        String reply = finalReply.path("reply").asText("").trim();
        boolean answered = finalReply.path("answered").asBoolean(false);
        String confidence = finalReply.path("confidence").asText("low");
        String intent = finalReply.path("intent").asText("OTHER");
        if (finalReply.path("needsHuman").asBoolean(false) || !answered || !"high".equals(confidence)) {
            String reason = finalReply.path("reason").asText("");
            session.attention.add(!reason.isBlank() ? reason : !answered ? "AI could not answer" : "AI was not confident");
        }
        if (reply.isBlank()) {
            reply = unknown;
            answered = false;
        }
        if (reply.length() > MAX_REPLY) reply = reply.substring(0, MAX_REPLY - 3) + "...";
        return result(reply, "AI", intent, answered, confidence, session);
    }

    private Result result(String reply, String source, String intent, boolean answered, String confidence,
                          WholesaleAssistantTools.Session session) {
        boolean needsHuman = !session.attention.isEmpty();
        return new Result(reply, source, intent, answered, confidence, needsHuman,
            needsHuman ? String.join("; ", new LinkedHashSet<>(session.attention)) : null,
            session.calls, session.context, session.quotation);
    }

    // ── Prompt + history ───────────────────────────────────────────────────────────────────────────

    private String systemPrompt(String firmName, WholesaleAssistantSettings settings, Map<String, Object> context,
                                boolean customerLinked, boolean dryRun) {
        StringBuilder ctx = new StringBuilder();
        if (context.get("productName") != null) {
            ctx.append("- Current product: ").append(context.get("productName")).append(" (productId ")
                .append(context.get("productId")).append(", sold per ").append(context.get("unit")).append(")\n");
        }
        if (context.get("quantity") != null) {
            ctx.append("- Quantity discussed: ").append(context.get("quantity")).append(' ')
                .append(Objects.toString(context.get("quantityUnit"), "")).append('\n');
        }
        if (context.get("quotationRef") != null) ctx.append("- Quotation already prepared: ").append(context.get("quotationRef")).append('\n');
        if (ctx.length() == 0) ctx.append("- (nothing yet)\n");

        return """
            You are the wholesale sales assistant of %s, a wholesale textile / fabric business in India, answering \
            customers in a chat (Instagram or similar). Customers may write English, Telugu, Hindi or a mix in \
            English letters (e.g. "Madras check 100 meters ki entha?").

            SOURCE OF TRUTH: the tools. You know NOTHING about products, prices, stock, minimum quantities, HSN, GST, \
            colours, designs, specifications, ordering, shipping, payment or the business except what tools return in \
            THIS conversation. Never guess, estimate, calculate new amounts or use general knowledge for these.
            - If a tool result does not contain the answer (field missing, listed in notAvailable, "not configured", \
            error, NOT_FOUND), reply with exactly this sentence for that part: "%s"
            - Prices are per unit and exclude GST unless the field says total / amountWithGst / totalWithGst. \
            Mention the GST %% separately.
            - Stock: say available / not available. State a quantity only if availableQuantity is present. If \
            enoughForRequested is false, say that quantity may not be available now and the team will confirm.
            - pricing.requestedStatus BELOW_MINIMUM → tell the minimumOrderQuantity. UNIT_MISMATCH → say it is sold \
            per <unit> and ask the quantity in that unit. NO_PRICE_FOR_QUANTITY / no slabs → the team will share the price.
            - Several possible products → ask which one (list names). Never pick one silently.
            - Never confirm an order, booking, discount, delivery date or payment; the team confirms those.
            - Quotation: only when the customer explicitly asks for one and products + quantities are clear, call \
            create_quotation_draft. A draft is reviewed and sent by our team — say so; never call it final unless the \
            tool says it was issued. If it was not created, say the team will prepare and send it.%s

            CONVERSATION CONTEXT (use it so the customer never has to repeat the product; "100 meters?", "price?", \
            "available?" refer to the current product and quantity unless they name another product):
            %s
            Use productId from the context or from search results; search first only when the product is not known.

            REPLY: finish every turn with exactly one send_reply call. Reply in the customer's language and script, \
            friendly and professional, plain text (no markdown, no numbered lists), at most 5 short sentences, under \
            600 characters, ₹ for rupees. Set answered=false and needsHuman=true when you used the "don't have that \
            information" sentence or the customer wants to order / complains / needs a person."""
            .formatted(firmName, settings.getUnknownReply(),
                dryRun ? "\n- (Test mode: quotations are only previewed, nothing is saved.)" : customerLinked ? "" :
                    "\n- This customer is not registered yet: create_quotation_draft will hand the request to the team.",
                ctx.toString());
    }

    /** Recent history (newest kept, within budget) in API format, alternating roles, ending with the new message. */
    private ArrayNode conversation(List<Turn> history, String message) {
        List<Turn> kept = new ArrayList<>();
        int chars = message.length();
        if (history != null) {
            for (int i = history.size() - 1; i >= 0 && kept.size() < MAX_HISTORY_TURNS; i--) {
                Turn t = history.get(i);
                if (t.text() == null || t.text().isBlank()) continue;
                chars += t.text().length();
                if (chars > MAX_HISTORY_CHARS) break;
                kept.add(0, t);
            }
        }
        while (!kept.isEmpty() && kept.get(0).role() != Role.CUSTOMER) kept.remove(0); // must start with the customer

        ArrayNode messages = json.createArrayNode();
        String lastRole = null;
        StringBuilder buffer = new StringBuilder();
        for (Turn t : kept) {
            String role = t.role() == Role.CUSTOMER ? "user" : "assistant";
            String text = t.role() == Role.STAFF ? "[Staff member wrote] " + t.text() : t.text();
            if (role.equals(lastRole)) {
                buffer.append('\n').append(text);
            } else {
                if (lastRole != null) messages.addObject().put("role", lastRole).put("content", buffer.toString());
                lastRole = role;
                buffer = new StringBuilder(text);
            }
        }
        if ("user".equals(lastRole)) {
            messages.addObject().put("role", "user").put("content", buffer + "\n" + message);
        } else {
            if (lastRole != null) messages.addObject().put("role", lastRole).put("content", buffer.toString());
            messages.addObject().put("role", "user").put("content", message);
        }
        return messages;
    }

    private ObjectNode toolResult(String id, String content, boolean isError) {
        ObjectNode r = json.createObjectNode();
        r.put("type", "tool_result");
        r.put("tool_use_id", id);
        r.put("content", content);
        if (isError) r.put("is_error", true);
        return r;
    }

    // ── Verification ───────────────────────────────────────────────────────────────────────────────

    /** null when every number in the reply comes from tool results, the context or the customer; else the reason. */
    String verify(String reply, WholesaleAssistantTools.Session session, Request request) {
        if (reply == null || reply.isBlank()) return "empty reply";
        Set<BigDecimal> allowed = new HashSet<>();
        try {
            allowed.addAll(numbers(json.writeValueAsString(session.calls)));
            allowed.addAll(numbers(json.writeValueAsString(session.context)));
        } catch (Exception e) {
            return "could not read tool results";
        }
        allowed.addAll(numbers(request.message()));
        allowed.addAll(numbers(session.settings.getUnknownReply()));
        if (request.history() != null) {
            for (Turn t : request.history()) if (t.role() == Role.CUSTOMER) allowed.addAll(numbers(t.text()));
        }
        for (BigDecimal n : List.copyOf(allowed)) {
            BigDecimal whole = n.setScale(0, RoundingMode.FLOOR); // slab bounds such as 99.999 are written as 99
            if (n.subtract(whole).compareTo(new BigDecimal("0.99")) >= 0) allowed.add(whole);
        }
        for (BigDecimal n : numbers(LIST_MARKER.matcher(reply).replaceAll(" "))) {
            if (!allowed.contains(n)) return "the number " + n.toPlainString() + " is not in any tool result";
        }
        return null;
    }

    static Set<BigDecimal> numbers(String text) {
        Set<BigDecimal> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String raw = m.group().replace(",", "");
            if (raw.isEmpty()) continue;
            try {
                out.add(WholesalePriceLookupService.plain(new BigDecimal(raw)));
            } catch (NumberFormatException ignored) {
                // not a number
            }
        }
        return out;
    }
}
