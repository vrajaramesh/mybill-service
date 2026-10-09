package com.example.mybill.wholesale.service;

import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.wholesale.config.WholesaleSchemaInitializer;
import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.entity.WholesaleCustomer;
import com.example.mybill.wholesale.entity.WholesaleIgConversation;
import com.example.mybill.wholesale.entity.WholesaleIgMessage;
import com.example.mybill.wholesale.entity.WholesaleIgMessage.Direction;
import com.example.mybill.wholesale.entity.WholesaleIgMessage.SenderType;
import com.example.mybill.wholesale.entity.WholesaleIgMessage.Status;
import com.example.mybill.wholesale.entity.WholesaleInstagramSettings;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleCustomerRepository;
import com.example.mybill.wholesale.repository.WholesaleIgConversationRepository;
import com.example.mybill.wholesale.repository.WholesaleIgMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/**
 * Wholesale Instagram inbox.
 *
 * <p>Webhook events are stored first (deduplicated by Instagram message id), then each customer message is answered on
 * a per-customer worker lane, so one customer's messages are handled in order while different customers run in
 * parallel. The AI ({@link WholesaleAssistantService}) reaches business data only through its controlled tools; this service stores what it
 * understood, the facts used and the reply, and sends the reply.
 *
 * <p>Human takeover: when staff reply (from this app or from the Instagram app itself) or press "Take over", the
 * conversation's AI is switched off until someone re-enables it. Messages that arrive meanwhile are stored with status
 * HUMAN_TAKEOVER and are never answered automatically.
 */
@Service
public class WholesaleInstagramMessagingService {

    public enum ReceiveResult { ACCEPTED, FORBIDDEN, FAILED }

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int HISTORY = 30;
    private static final int LANES = 4;

    @Autowired private WholesaleInstagramSettingsService settingsService;
    @Autowired private WholesaleIgConversationRepository conversationRepository;
    @Autowired private WholesaleIgMessageRepository messageRepository;
    @Autowired private WholesaleCustomerRepository customerRepository;
    @Autowired private WholesaleAssistantService assistant;
    @Autowired private WholesaleInstagramClient client;
    @Autowired private WholesaleSchemaInitializer schemaInitializer;

    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService intake = Executors.newFixedThreadPool(4, named("wholesale-ig-intake"));
    private final ExecutorService[] lanes = new ExecutorService[LANES];

    public WholesaleInstagramMessagingService(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        for (int i = 0; i < LANES; i++) lanes[i] = Executors.newSingleThreadExecutor(named("wholesale-ig-lane-" + i));
    }

    @PreDestroy
    void shutdown() {
        intake.shutdown();
        for (ExecutorService lane : lanes) lane.shutdown();
    }

    // ── Webhook ────────────────────────────────────────────────────────────────────────────────────

    /** Subscription handshake: the configured verify token, or null when messaging is not set up. */
    public String verifyToken(String schema) {
        Future<String> f = intake.submit(() -> inTenant(schema, () -> {
            schemaInitializer.ensureSchema(schema);
            WholesaleInstagramSettings s = settingsService.current();
            return s.getVerifyToken();
        }));
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[WholesaleInstagram] Webhook verification failed for " + schema + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Stores a webhook delivery and queues replies. Runs on a worker thread (the HTTP request's JPA session belongs to
     * the public schema) and waits for the messages to be stored, so a failure makes Meta retry the delivery.
     */
    public ReceiveResult receive(String schema, byte[] body, String signature) {
        Future<ReceiveResult> f = intake.submit(() -> inTenant(schema, () -> ingest(schema, body, signature)));
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReceiveResult.FAILED;
        } catch (Exception e) {
            System.err.println("[WholesaleInstagram] Webhook failed for " + schema + ": " + e.getMessage());
            return ReceiveResult.FAILED;
        }
    }

    private ReceiveResult ingest(String schema, byte[] body, String signature) throws Exception {
        schemaInitializer.ensureSchema(schema);
        WholesaleInstagramSettings settings = settingsService.current();
        if (!Boolean.TRUE.equals(settings.getIsEnabled())) return ReceiveResult.ACCEPTED; // ignored, no retries
        if (!WholesaleInstagramClient.validSignature(settings.getAppSecret(), body, signature)) {
            System.err.println("[WholesaleInstagram] Rejected webhook with invalid signature for " + schema);
            return ReceiveResult.FORBIDDEN;
        }
        JsonNode root = json.readTree(body);
        String account = settings.getInstagramAccountId();
        List<Queued> toAnswer = new ArrayList<>();

        for (JsonNode entry : root.path("entry")) {
            if (account != null && !account.equals(entry.path("id").asText())) continue;
            for (JsonNode event : entry.path("messaging")) {
                JsonNode message = event.path("message");
                if (message.isMissingNode() || message.path("is_deleted").asBoolean(false)) continue; // reads, reactions…
                String mid = message.path("mid").asText(null);
                String text = message.hasNonNull("text") ? message.path("text").asText() : null;
                List<Map<String, Object>> attachments = message.has("attachments")
                    ? json.convertValue(message.path("attachments"), new TypeReference<List<Map<String, Object>>>() {}) : null;
                LocalDateTime at = event.hasNonNull("timestamp")
                    ? LocalDateTime.ofInstant(Instant.ofEpochMilli(event.path("timestamp").asLong()), BUSINESS_ZONE)
                    : LocalDateTime.now(BUSINESS_ZONE);
                String sender = event.path("sender").path("id").asText();
                String recipient = event.path("recipient").path("id").asText();

                if (message.path("is_echo").asBoolean(false)) {
                    recordEcho(recipient, mid, text, attachments, at);
                } else if (account == null || !account.equals(sender)) {
                    Queued q = recordInbound(sender, mid, text, attachments, at);
                    if (q != null) toAnswer.add(q);
                }
            }
        }
        for (Queued q : toAnswer) {
            lane(q.igUser()).submit(() -> inTenant(schema, () -> {
                try {
                    if (q.unknownProfile()) fillProfile(settings, q.igUser());
                    process(q.messageId());
                } catch (Exception e) {
                    System.err.println("[WholesaleInstagram] Processing message " + q.messageId() + " failed: " + e.getMessage());
                }
                return null;
            }));
        }
        return ReceiveResult.ACCEPTED;
    }

    private record Queued(Long messageId, String igUser, boolean unknownProfile) {}

    /** Stores a customer message; null when it was already stored (webhook retry). */
    private Queued recordInbound(String igUser, String mid, String text, List<Map<String, Object>> attachments, LocalDateTime at) {
        return tx.execute(st -> {
            if (mid != null && messageRepository.existsByInstagramMessageId(mid)) return null;
            WholesaleIgConversation c = conversation(igUser);
            WholesaleIgMessage m = new WholesaleIgMessage();
            m.setConversation(c);
            m.setInstagramMessageId(mid);
            m.setDirection(Direction.INBOUND);
            m.setSenderType(SenderType.CUSTOMER);
            m.setMessageText(text);
            m.setAttachments(attachments);
            m.setStatus(Status.RECEIVED);
            m.setMessageAt(at);
            messageRepository.save(m);
            c.touch(text != null ? text : "[attachment]", at, true);
            return new Queued(m.getMessageId(), igUser, c.getInstagramUsername() == null && c.getDisplayName() == null);
        });
    }

    /**
     * Echo of a message sent from the business account. Our own replies are matched and confirmed; anything else was
     * typed by staff in the Instagram app, which counts as a human takeover.
     */
    private void recordEcho(String igUser, String mid, String text, List<Map<String, Object>> attachments, LocalDateTime at) {
        tx.executeWithoutResult(st -> {
            if (mid != null && messageRepository.existsByInstagramMessageId(mid)) return;
            WholesaleIgConversation c = conversation(igUser);
            if (text != null) {
                List<WholesaleIgMessage> ours = messageRepository.findUnconfirmedOutbound(
                    c.getConversationId(), Direction.OUTBOUND, text, LocalDateTime.now().minusMinutes(10));
                if (!ours.isEmpty()) {
                    WholesaleIgMessage m = ours.get(0);
                    m.setInstagramMessageId(mid);
                    if (m.getStatus() == Status.PENDING) m.setStatus(Status.SENT);
                    return;
                }
            }
            WholesaleIgMessage m = new WholesaleIgMessage();
            m.setConversation(c);
            m.setInstagramMessageId(mid);
            m.setDirection(Direction.OUTBOUND);
            m.setSenderType(SenderType.STAFF);
            m.setMessageText(text);
            m.setAttachments(attachments);
            m.setStatus(Status.SENT);
            m.setSentBy("Instagram app");
            m.setMessageAt(at);
            messageRepository.save(m);
            c.touch(text != null ? text : "[attachment]", at, false);
            if (Boolean.TRUE.equals(c.getAiEnabled())) {
                c.setAiEnabled(false);
                c.setTakeoverBy("Instagram app");
                c.setTakeoverAt(LocalDateTime.now());
            }
        });
    }

    private WholesaleIgConversation conversation(String igUser) {
        conversationRepository.insertIfAbsent(igUser);
        Integer id = conversationRepository.findByInstagramUserId(igUser).orElseThrow().getConversationId();
        return conversationRepository.findByIdForUpdate(id).orElseThrow();
    }

    private void fillProfile(WholesaleInstagramSettings settings, String igUser) {
        WholesaleInstagramClient.Profile p = client.profile(settings, igUser);
        if (p == null || (p.username() == null && p.name() == null)) return;
        tx.executeWithoutResult(st -> conversationRepository.findByInstagramUserId(igUser).ifPresent(c -> {
            if (c.getInstagramUsername() == null) c.setInstagramUsername(p.username());
            if (c.getDisplayName() == null) c.setDisplayName(p.name());
        }));
    }

    // ── Automatic reply ────────────────────────────────────────────────────────────────────────────

    private record Work(String text, Integer conversationId, String igUser, List<WholesaleAssistantService.Turn> history,
                        Map<String, Object> context, Integer customerId, String handoff) {}

    /** Answers one stored customer message (on its customer's lane). */
    void process(Long messageId) {
        WholesaleInstagramSettings settings = settingsService.current();
        Work work = tx.execute(st -> {
            WholesaleIgMessage m = messageRepository.findById(messageId).orElse(null);
            if (m == null || m.getStatus() != Status.RECEIVED) return null;
            WholesaleIgConversation c = conversationRepository.findByIdForUpdate(m.getConversation().getConversationId()).orElseThrow();
            if (m.getMessageText() == null || m.getMessageText().isBlank()) {
                m.setStatus(Status.NEEDS_HUMAN);
                c.flag("Customer sent an attachment");
                return null;
            }
            if (!Boolean.TRUE.equals(c.getAiEnabled())) {
                m.setStatus(Status.HUMAN_TAKEOVER);
                c.flag("New message during human takeover");
                return null;
            }
            if (!Boolean.TRUE.equals(settings.getAutoReplyEnabled())) {
                m.setStatus(Status.AI_DISABLED);
                c.flag("Auto-reply is off");
                return null;
            }
            m.setStatus(Status.PROCESSING);
            List<WholesaleAssistantService.Turn> history = new ArrayList<>(messageRepository
                .findHistory(c.getConversationId(), messageId, PageRequest.of(0, HISTORY)).stream()
                .map(WholesaleInstagramMessagingService::turn)
                .toList());
            Collections.reverse(history);
            Map<String, Object> context = c.getAssistantContext() != null ? new LinkedHashMap<>(c.getAssistantContext())
                : c.getLastProductId() != null ? new LinkedHashMap<>(Map.of("productId", c.getLastProductId())) : new LinkedHashMap<>();
            return new Work(m.getMessageText(), c.getConversationId(), c.getInstagramUserId(), history, context,
                c.getCustomer() != null ? c.getCustomer().getWholesaleCustomerId() : null, settings.getHandoffMessage());
        });
        if (work == null) return;

        WholesaleAssistantService.Result result;
        try {
            result = assistant.chat(new WholesaleAssistantService.Request(work.text(), work.history(), work.context(),
                work.customerId(), "Instagram", false));
        } catch (Exception e) {
            tx.executeWithoutResult(st -> messageRepository.findById(messageId).ifPresent(m -> {
                m.fail("Assistant error: " + e.getMessage());
                m.getConversation().flag("AI could not process a message");
            }));
            return;
        }

        // AI service down → the channel's holding message instead of the generic "don't know" sentence
        String reply = truncate("ERROR".equals(result.replySource()) ? work.handoff() : result.reply());
        Long outboundId = tx.execute(st -> {
            WholesaleIgMessage m = messageRepository.findById(messageId).orElseThrow();
            WholesaleIgConversation c = conversationRepository.findByIdForUpdate(work.conversationId()).orElseThrow();
            Map<String, Object> extracted = new LinkedHashMap<>();
            extracted.put("intent", result.intent());
            extracted.put("answered", result.answered());
            extracted.put("confidence", result.confidence());
            extracted.put("replySource", result.replySource());
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("toolCalls", result.toolCalls());
            facts.put("context", result.context());
            if (result.quotation() != null) facts.put("quotation", result.quotation());
            m.setIntent(result.intent());
            m.setExtracted(extracted);
            m.setFacts(facts);
            m.setAiResponse(reply);
            c.setAssistantContext(result.context());
            if (result.context().get("productId") instanceof Number id) c.setLastProductId(id.intValue());
            if (result.needsHuman()) c.flag(result.attentionReason());
            if (!Boolean.TRUE.equals(c.getAiEnabled())) { // staff took over while the AI was thinking
                m.setStatus(Status.HUMAN_TAKEOVER);
                return null;
            }
            WholesaleIgMessage out = outbound(c, reply, SenderType.AI, null);
            m.setReplyMessageId(out.getMessageId());
            m.setStatus(Status.REPLIED);
            return out.getMessageId();
        });
        if (outboundId == null) return;

        try {
            String mid = client.sendText(settings, work.igUser(), reply);
            tx.executeWithoutResult(st -> markSent(outboundId, mid));
        } catch (Exception e) {
            tx.executeWithoutResult(st -> {
                messageRepository.findById(outboundId).ifPresent(o -> o.fail(e.getMessage()));
                messageRepository.findById(messageId).ifPresent(m -> {
                    m.fail("Reply could not be sent: " + e.getMessage());
                    m.getConversation().flag("Automatic reply failed to send");
                });
            });
        }
    }

    // ── Staff actions ──────────────────────────────────────────────────────────────────────────────

    public List<WholesaleIgConversationSummary> list(String q, String filter) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return tx.execute(st -> conversationRepository.findAllForInbox().stream()
            .filter(c -> switch (filter == null ? "" : filter.toUpperCase(Locale.ROOT)) {
                case "ATTENTION" -> Boolean.TRUE.equals(c.getNeedsAttention());
                case "TAKEOVER" -> !Boolean.TRUE.equals(c.getAiEnabled());
                case "UNREAD" -> c.getUnreadCount() != null && c.getUnreadCount() > 0;
                default -> true;
            })
            .filter(c -> needle.isEmpty() || contains(c.getInstagramUsername(), needle) || contains(c.getDisplayName(), needle)
                || contains(customerName(c.getCustomer()), needle) || contains(c.getLastMessageText(), needle)
                || contains(c.getInstagramUserId(), needle))
            .map(WholesaleInstagramMessagingService::summary)
            .toList());
    }

    public WholesaleIgConversationResponse get(Integer id) {
        return tx.execute(st -> {
            WholesaleIgConversation c = find(id);
            List<WholesaleIgMessageResponse> messages = messageRepository
                .findByConversation_ConversationIdOrderByMessageAtAscMessageIdAsc(id).stream()
                .map(WholesaleInstagramMessagingService::message).toList();
            return new WholesaleIgConversationResponse(summary(c), messages);
        });
    }

    /** Staff reply from the inbox. Replying switches the AI off for this conversation (human takeover). */
    public WholesaleIgMessageResponse sendStaffMessage(Integer id, String text, String username) {
        WholesaleInstagramSettings settings = settingsService.current();
        if (!Boolean.TRUE.equals(settings.getIsEnabled())) throw WholesaleException.badRequest("Instagram messaging is not enabled");
        String body = truncate(text.trim());
        record Pending(Long messageId, String igUser) {}
        Pending p = tx.execute(st -> {
            WholesaleIgConversation c = lock(id);
            takeover(c, username);
            c.setNeedsAttention(false);
            c.setAttentionReason(null);
            c.setUnreadCount(0);
            WholesaleIgMessage out = outbound(c, body, SenderType.STAFF, username);
            return new Pending(out.getMessageId(), c.getInstagramUserId());
        });
        try {
            String mid = client.sendText(settings, p.igUser(), body);
            tx.executeWithoutResult(st -> markSent(p.messageId(), mid));
        } catch (Exception e) {
            tx.executeWithoutResult(st -> messageRepository.findById(p.messageId()).ifPresent(o -> o.fail(e.getMessage())));
        }
        return tx.execute(st -> message(messageRepository.findById(p.messageId()).orElseThrow()));
    }

    public WholesaleIgConversationSummary takeover(Integer id, String username) {
        return tx.execute(st -> {
            WholesaleIgConversation c = lock(id);
            takeover(c, username);
            return summary(c);
        });
    }

    /** Re-enables automatic replies; messages received during the takeover are not answered retroactively. */
    public WholesaleIgConversationSummary release(Integer id) {
        return tx.execute(st -> {
            WholesaleIgConversation c = lock(id);
            c.setAiEnabled(true);
            c.setTakeoverBy(null);
            c.setTakeoverAt(null);
            return summary(c);
        });
    }

    public WholesaleIgConversationSummary markRead(Integer id) {
        return tx.execute(st -> {
            WholesaleIgConversation c = lock(id);
            c.setUnreadCount(0);
            return summary(c);
        });
    }

    public WholesaleIgConversationSummary resolveAttention(Integer id) {
        return tx.execute(st -> {
            WholesaleIgConversation c = lock(id);
            c.setNeedsAttention(false);
            c.setAttentionReason(null);
            return summary(c);
        });
    }

    public WholesaleIgConversationSummary linkCustomer(Integer id, Integer customerId) {
        return tx.execute(st -> {
            WholesaleIgConversation c = lock(id);
            if (customerId == null) {
                c.setCustomer(null);
            } else {
                WholesaleCustomer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> WholesaleException.notFound("Wholesale customer #" + customerId + " not found"));
                c.setCustomer(customer);
            }
            return summary(c);
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────────

    private WholesaleIgMessage outbound(WholesaleIgConversation c, String text, SenderType sender, String username) {
        WholesaleIgMessage out = new WholesaleIgMessage();
        out.setConversation(c);
        out.setDirection(Direction.OUTBOUND);
        out.setSenderType(sender);
        out.setMessageText(text);
        out.setStatus(Status.PENDING);
        out.setSentBy(username);
        out.setMessageAt(LocalDateTime.now(BUSINESS_ZONE));
        messageRepository.saveAndFlush(out);
        c.touch(text, out.getMessageAt(), false);
        return out;
    }

    private void markSent(Long outboundId, String mid) {
        messageRepository.findById(outboundId).ifPresent(o -> {
            if (mid != null && o.getInstagramMessageId() == null && !messageRepository.existsByInstagramMessageId(mid)) {
                o.setInstagramMessageId(mid);
            }
            o.setStatus(Status.SENT);
            o.setErrorMessage(null);
        });
    }

    private static void takeover(WholesaleIgConversation c, String username) {
        if (Boolean.TRUE.equals(c.getAiEnabled()) || c.getTakeoverBy() == null) {
            c.setAiEnabled(false);
            c.setTakeoverBy(username);
            c.setTakeoverAt(LocalDateTime.now());
        }
    }

    private WholesaleIgConversation find(Integer id) {
        return conversationRepository.findById(id)
            .orElseThrow(() -> WholesaleException.notFound("Instagram conversation #" + id + " not found"));
    }

    private WholesaleIgConversation lock(Integer id) {
        return conversationRepository.findByIdForUpdate(id)
            .orElseThrow(() -> WholesaleException.notFound("Instagram conversation #" + id + " not found"));
    }

    static WholesaleIgConversationSummary summary(WholesaleIgConversation c) {
        WholesaleCustomer customer = c.getCustomer();
        boolean windowOpen = c.getLastInboundAt() != null
            && c.getLastInboundAt().isAfter(LocalDateTime.now(BUSINESS_ZONE).minusHours(24));
        return new WholesaleIgConversationSummary(c.getConversationId(), c.getInstagramUserId(), c.getInstagramUsername(),
            c.getDisplayName(), customer == null ? null : customer.getWholesaleCustomerId(), customerName(customer),
            c.getAiEnabled(), c.getTakeoverBy(), c.getTakeoverAt(), c.getNeedsAttention(), c.getAttentionReason(),
            c.getUnreadCount(), c.getLastMessageText(), c.getLastMessageAt(), c.getLastInboundAt(), windowOpen);
    }

    static WholesaleIgMessageResponse message(WholesaleIgMessage m) {
        return new WholesaleIgMessageResponse(m.getMessageId(), m.getDirection().name(), m.getSenderType().name(),
            m.getMessageText(), m.getAttachments(), m.getIntent(), m.getExtracted(), m.getFacts(), m.getAiResponse(),
            m.getReplyMessageId(), m.getStatus().name(), m.getErrorMessage(), m.getSentBy(), m.getMessageAt());
    }

    private static WholesaleAssistantService.Turn turn(WholesaleIgMessage m) {
        WholesaleAssistantService.Role role = m.getDirection() == Direction.INBOUND ? WholesaleAssistantService.Role.CUSTOMER
            : m.getSenderType() == SenderType.STAFF ? WholesaleAssistantService.Role.STAFF : WholesaleAssistantService.Role.ASSISTANT;
        return new WholesaleAssistantService.Turn(role, m.getMessageText());
    }

    private static String customerName(WholesaleCustomer c) {
        if (c == null) return null;
        return c.getBusinessName() != null && !c.getBusinessName().isBlank()
            ? c.getCustomerName() + " (" + c.getBusinessName() + ")" : c.getCustomerName();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String truncate(String text) {
        int max = WholesaleInstagramClient.MAX_TEXT;
        return text.length() > max ? text.substring(0, max - 3) + "..." : text;
    }

    private ExecutorService lane(String igUser) {
        return lanes[Math.floorMod(igUser.hashCode(), LANES)];
    }

    private static <T> T inTenant(String schema, Callable<T> work) throws RuntimeException {
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schema);
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setCurrentTenant(previous);
        }
    }

    private static ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
