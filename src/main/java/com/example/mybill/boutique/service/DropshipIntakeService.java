package com.example.mybill.boutique.service;

import com.example.mybill.boutique.entity.WaIntakeBatch;
import com.example.mybill.boutique.entity.WaIntakeMessage;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.WaIntakeBatchRepository;
import com.example.mybill.boutique.repository.WaIntakeMessageRepository;
import com.example.mybill.service.GcsUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WhatsApp → Dropship intake (phase 2). The owner forwards a supplier's post (text + one photo per colour) to the
 * shop's number; srisa-agent (which owns the WhatsApp webhook) hands each message to {@link #intake}. Messages of one
 * sender form one COLLECTING batch until {@code dropship.intake.idle-seconds} pass without a new one; then
 * {@link #processBatches} (called by a delayed Cloud Task, so it works with min-instances=0) closes it and asks the
 * owner which supplier it is from - every time, never automatically (forwards don't carry the original sender): the
 * first photo with the post's text, then a WhatsApp list of the dropship-active suppliers, most recently used first,
 * 9 per page + "More suppliers" (AWAITING_SUPPLIER; a reminder after 2 h). The choice sets the batch's (and its
 * collection's) supplier and the batch moves on to AI extraction (phase 3, status EXTRACTING).
 */
@Service
public class DropshipIntakeService {

    private static final Logger log = LoggerFactory.getLogger(DropshipIntakeService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    /** List row ids: "{batchId}:{supplierId}" or "{batchId}:MORE:{page}". */
    private static final Pattern SUPPLIER_ROW = Pattern.compile("^(\\d+):(?:(MORE):)?(\\d+)$");
    private static final int PAGE_SIZE = 9;               // + "More suppliers" = WhatsApp's 10-row list limit
    private static final int REMIND_AFTER_HOURS = 2;

    @Autowired private WaIntakeBatchRepository batches;
    @Autowired private WaIntakeMessageRepository messages;
    @Autowired private DropshipService dropship;
    @Autowired private GcsUploadService gcs;
    @Autowired private ApplicationEventPublisher events;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    @Value("${dropship.intake.idle-seconds:60}") private int idleSeconds;

    /** One message from srisa-agent. image = the photo bytes (already downloaded from WhatsApp). */
    public record Incoming(String waMessageId, String from, String type, String text, String mediaId, String mimeType,
                           Long timestamp, byte[] image) {}

    /** Sent to the owner's WhatsApp by srisa-agent after the DB commit (event "owner.message"). */
    public record OwnerMessage(String to, String eventId, String kind, String text, String buttonText,
                               List<Map<String, Object>> sections, String imageUrl, String imageCaption) {}

    // ── intake ───────────────────────────────────────────────────────────────

    /** Saves the message in the sender's collecting batch (a redelivery is ignored). {status, batch_id, photo_no}. */
    @Transactional
    public Map<String, Object> intake(Incoming in) {
        String from = digits(in.from());
        if (in.waMessageId() == null || in.waMessageId().isBlank() || from.isEmpty()) throw bad("wa_message_id and from are required");
        String type = in.type() == null ? "" : in.type().toLowerCase(Locale.ROOT);
        if (!type.equals(WaIntakeMessage.TEXT) && !type.equals(WaIntakeMessage.IMAGE)) throw bad("type must be text or image");
        if (type.equals(WaIntakeMessage.IMAGE) && (in.image() == null || in.image().length == 0)) throw bad("image bytes are required");

        Optional<WaIntakeMessage> dup = messages.findByWaMessageId(in.waMessageId());
        if (dup.isPresent()) return result("duplicate", dup.get().getBatchId(), null);

        // A parallel first message may create the batch at the same time: the unique index then fails this request
        // and the agent's task retries it into the existing batch.
        WaIntakeBatch open = batches.findFirstByFromNumberAndStatus(from, WaIntakeBatch.COLLECTING).orElseGet(() -> {
            WaIntakeBatch b = new WaIntakeBatch();
            b.setFromNumber(from);
            return batches.saveAndFlush(b);
        });
        WaIntakeBatch batch = lock(open);
        if (!WaIntakeBatch.COLLECTING.equals(batch.getStatus())) {
            throw new AgentApiException(HttpStatus.SERVICE_UNAVAILABLE, "BATCH_CLOSING", "The post was just closed - retry", null);
        }
        batch.setUpdatedAtNow();   // the idle timer restarts with every message

        WaIntakeMessage m = new WaIntakeMessage();
        m.setWaMessageId(in.waMessageId());
        m.setBatchId(batch.getBatchId());
        m.setFromNumber(from);
        m.setMsgType(type);
        m.setText(blankToNull(in.text()));
        m.setMediaId(in.mediaId());
        m.setMimeType(in.mimeType());
        if (in.timestamp() != null) m.setReceivedAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(in.timestamp()), ZONE));
        Integer photoNo = null;
        if (type.equals(WaIntakeMessage.IMAGE)) {
            photoNo = (int) messages.findByBatchIdOrderByReceivedAtAscMessageIdAsc(batch.getBatchId()).stream()
                .filter(x -> WaIntakeMessage.IMAGE.equals(x.getMsgType())).count() + 1;
            String ext = in.mimeType() != null && in.mimeType().contains("png") ? "png" : "jpg";
            m.setGcsPath(gcs.uploadToPath(in.image(), in.mimeType(),
                "dropship/" + batch.getBatchId() + "/original/" + photoNo + "." + ext));
        }
        messages.save(m);
        log.info("DROPSHIP_INTAKE batch={} type={} photo={}", batch.getBatchId(), type, photoNo);
        return result("saved", batch.getBatchId(), photoNo);
    }

    // ── closing batches ──────────────────────────────────────────────────────

    /**
     * Closes collecting batches that have been quiet for idle-seconds (force = close the sender's batch now, e.g. the
     * owner typed "done"). Returns {closed, awaiting_supplier, extracting, failed}.
     */
    @Transactional
    public Map<String, Object> processBatches(String forceFrom) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(idleSeconds, 5));
        List<WaIntakeBatch> due = new ArrayList<>(batches.findByStatusAndUpdatedAtBefore(WaIntakeBatch.COLLECTING, cutoff));
        if (forceFrom != null) batches.findFirstByFromNumberAndStatus(digits(forceFrom), WaIntakeBatch.COLLECTING)
            .filter(b -> due.stream().noneMatch(d -> d.getBatchId().equals(b.getBatchId()))).ifPresent(due::add);
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("closed", 0, "awaiting_supplier", 0, "extracting", 0, "failed", 0));
        List<Integer> asked = new ArrayList<>();
        for (WaIntakeBatch candidate : due) {
            // Lock, then re-check: a photo may have arrived (or another task closed it) since the query.
            WaIntakeBatch b = lock(candidate);
            if (!WaIntakeBatch.COLLECTING.equals(b.getStatus())) continue;
            boolean forced = forceFrom != null && digits(forceFrom).equals(b.getFromNumber());
            if (!forced && b.getUpdatedAt().isAfter(cutoff)) continue;
            close(b);
            counts.merge("closed", 1, Integer::sum);
            counts.merge(b.getStatus().toLowerCase(Locale.ROOT), 1, Integer::sum);
            if (WaIntakeBatch.AWAITING_SUPPLIER.equals(b.getStatus())) asked.add(b.getBatchId());
        }
        Map<String, Object> out = new LinkedHashMap<>(counts);
        out.put("awaiting_supplier_ids", asked);   // the agent schedules a reminder task for each
        out.put("reminded", remindDue());
        return out;
    }

    /** Re-sends the supplier list once for posts still waiting 2 h after the question. Returns how many. */
    @Transactional
    public int remindDue() {
        int n = 0;
        for (WaIntakeBatch b : batches.findByStatusAndSupplierAskedAtBeforeAndRemindedAtIsNull(
                WaIntakeBatch.AWAITING_SUPPLIER, LocalDateTime.now().minusHours(REMIND_AFTER_HOURS))) {
            b.setRemindedAt(LocalDateTime.now());
            askSupplier(b, messages.findByBatchIdOrderByReceivedAtAscMessageIdAsc(b.getBatchId()), 0, true);
            n++;
        }
        return n;
    }

    private void close(WaIntakeBatch b) {
        b.setClosedAt(LocalDateTime.now());
        List<WaIntakeMessage> msgs = messages.findByBatchIdOrderByReceivedAtAscMessageIdAsc(b.getBatchId());
        long photos = msgs.stream().filter(m -> WaIntakeMessage.IMAGE.equals(m.getMsgType())).count();
        int textLength = msgs.stream().mapToInt(m -> m.getText() == null ? 0 : m.getText().trim().length()).sum();
        if (photos == 0 && textLength < 20) {   // e.g. "hi" to keep the 24 h window open for staff alerts: not a post
            b.setStatus(WaIntakeBatch.SKIPPED);
            b.setError("Short text only - not a supplier post");
            return;
        }
        if (photos == 0) {
            b.setStatus(WaIntakeBatch.FAILED);
            b.setError("No photos in the forwarded post");
            tell(b, "text", "⚠️ Post #" + b.getBatchId() + ": I got only text, no photos. Forward the supplier's photos too "
                + "(the text and all photos together).", null, null);
            return;
        }
        b.setStatus(WaIntakeBatch.AWAITING_SUPPLIER);   // the owner chooses every time - never assigned automatically
        b.setSupplierAskedAt(LocalDateTime.now());
        askSupplier(b, msgs, 0, false);
    }

    /**
     * The first photo with the post's text as caption, then the list: dropship-active suppliers, most recently used
     * first, 9 per page + "More suppliers". page = 0-based.
     */
    private void askSupplier(WaIntakeBatch b, List<WaIntakeMessage> msgs, int page, boolean reminder) {
        long photos = msgs.stream().filter(m -> WaIntakeMessage.IMAGE.equals(m.getMsgType())).count();
        String postText = msgs.stream().map(WaIntakeMessage::getText).filter(t -> t != null && !t.isBlank()).findFirst().orElse("");
        String snippet = postText.replaceAll("\\s+", " ").trim();
        if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "…";
        String firstPhoto = msgs.stream().filter(m -> WaIntakeMessage.IMAGE.equals(m.getMsgType()) && m.getGcsPath() != null)
            .map(WaIntakeMessage::getGcsPath).findFirst().orElse(null);

        List<DropshipService.Supplier> all = dropship.dropshipChoices();
        String head = (reminder ? "⏰ Reminder – still waiting: " : "📦 ") + "Post #" + b.getBatchId() + " · " + photos + " photo(s)"
            + (snippet.isEmpty() ? "" : "\n" + snippet);
        if (all.isEmpty()) {
            tell(b, "text", head + "\n\nNo supplier is active for dropship. Add one in mybill (Collections → 🏭 Suppliers, "
                + "\"Active for dropship\"), or reply: NEW SUPPLIER: Name, 10-digit WhatsApp number, pincode", null, null,
                page == 0 ? firstPhoto : null);
            return;
        }
        int from = Math.min(page * PAGE_SIZE, Math.max(all.size() - 1, 0));
        List<DropshipService.Supplier> shown = all.subList(from, Math.min(from + PAGE_SIZE, all.size()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DropshipService.Supplier s : shown) {
            rows.add(row(b.getBatchId() + ":" + s.id(), s.name(),
                String.join(" · ", java.util.stream.Stream.of(s.phone(), s.pincode()).filter(Objects::nonNull).toList())));
        }
        if (from + PAGE_SIZE < all.size()) {
            rows.add(row(b.getBatchId() + ":MORE:" + (page + 1), "More suppliers ›",
                (from + PAGE_SIZE + 1) + "–" + Math.min(from + 2 * PAGE_SIZE, all.size()) + " of " + all.size()));
        }
        tell(b, "list", head + "\n\nWhich supplier is this from?", "Choose supplier",
            List.of(Map.of("title", page == 0 ? "Suppliers" : "Suppliers (page " + (page + 1) + ")", "rows", rows)),
            page == 0 ? firstPhoto : null);
    }

    // ── the owner's answers ──────────────────────────────────────────────────

    /**
     * A reply from the owner: a tapped list row (reply_id "dsup:<batch>:<supplier|new>"), "done" (close the current
     * post now), "NEW SUPPLIER: name, phone, pincode" or "SUPPLIER <name or phone>". {handled, ...}.
     */
    @Transactional
    public Map<String, Object> ownerReply(String from, String replyId, String text) {
        String owner = digits(from);
        String t = text == null ? "" : text.trim();
        Matcher row = replyId == null ? null : SUPPLIER_ROW.matcher(replyId.trim());
        if (row != null && row.matches()) {
            WaIntakeBatch b = batches.findById(parseInt(row.group(1))).orElse(null);
            if (b == null || !WaIntakeBatch.AWAITING_SUPPLIER.equals(b.getStatus())) {
                return Map.of("handled", false, "reason", "batch_not_waiting");
            }
            if (row.group(2) != null) {   // "More suppliers"
                askSupplier(b, messages.findByBatchIdOrderByReceivedAtAscMessageIdAsc(b.getBatchId()), parseInt(row.group(3)), false);
                return Map.of("handled", true, "action", "next_page");
            }
            DropshipService.Supplier s = dropship.supplier(parseInt(row.group(3))).orElse(null);
            if (s == null) return Map.of("handled", false, "reason", "unknown_supplier");
            assign(b, s, photoCount(b), "chosen by you");
            return Map.of("handled", true, "action", "supplier_set", "batch_id", b.getBatchId());
        }
        if (t.equalsIgnoreCase("done")) {
            return Map.of("handled", true, "action", "closed", "result", processBatches(owner));
        }
        Optional<WaIntakeBatch> waiting = batches.findTop50ByOrderByCreatedAtDesc().stream()
            .filter(b -> owner.equals(b.getFromNumber()) && WaIntakeBatch.AWAITING_SUPPLIER.equals(b.getStatus()))
            .reduce((newer, older) -> older);   // the oldest post still waiting
        if (t.toUpperCase(Locale.ROOT).startsWith("NEW SUPPLIER")) {
            if (waiting.isEmpty()) return Map.of("handled", false, "reason", "no_post_waiting");
            String[] f = t.replaceFirst("(?i)^new supplier\\s*:?", "").split(",");
            if (f.length < 2) {
                tell(waiting.get(), "text", "Please send: NEW SUPPLIER: Name, 10-digit WhatsApp number, pincode", null, null);
                return Map.of("handled", true, "action", "format_help");
            }
            Map<String, Object> body = new HashMap<>();
            body.put("name", f[0].trim());
            // Checked here: a validation error inside saveSupplier would mark this whole transaction rollback-only.
            String phone = digits(f[1]), pin = f.length > 2 ? digits(f[2]) : "";
            if (phone.length() == 12 && phone.startsWith("91")) phone = phone.substring(2);
            if (f[0].isBlank() || phone.length() != 10 || (!pin.isEmpty() && pin.length() != 6)) {
                tell(waiting.get(), "text", "⚠️ I need a name, a 10-digit WhatsApp number and (optional) a 6-digit pincode. "
                    + "Please send it again: NEW SUPPLIER: Name, number, pincode", null, null);
                return Map.of("handled", true, "action", "supplier_invalid");
            }
            body.put("phone", phone);
            if (!pin.isEmpty()) body.put("pincode", pin);
            DropshipService.Supplier s = dropship.saveSupplier(null, body);
            assign(waiting.get(), s, photoCount(waiting.get()), "new supplier added");
            return Map.of("handled", true, "action", "supplier_created", "supplier_id", s.id(), "batch_id", waiting.get().getBatchId());
        }
        if (t.toUpperCase(Locale.ROOT).startsWith("SUPPLIER ") && waiting.isPresent()) {
            String q = t.substring(9).trim().toLowerCase(Locale.ROOT);
            String qDigits = last10(q);
            List<DropshipService.Supplier> found = dropship.suppliers().stream().filter(DropshipService.Supplier::active)
                .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(q) || (qDigits.length() == 10 && qDigits.equals(last10(s.phone()))))
                .toList();
            if (found.size() == 1) {
                assign(waiting.get(), found.get(0), photoCount(waiting.get()), "chosen by you");
                return Map.of("handled", true, "action", "supplier_set", "batch_id", waiting.get().getBatchId());
            }
            tell(waiting.get(), "text", found.isEmpty() ? "No supplier matches \"" + q + "\"." : "Several suppliers match \""
                + q + "\" - send a longer name or their phone.", null, null);
            return Map.of("handled", true, "action", "supplier_ambiguous");
        }
        return Map.of("handled", false, "reason", "not_an_owner_command");
    }

    private void assign(WaIntakeBatch b, DropshipService.Supplier s, long photos, String how) {
        b.setSupplierId(s.id());
        dropship.markUsed(s.id());
        if (b.getCollectionId() != null) {
            em.createNativeQuery("UPDATE boutique_collections SET supplier_id = ?1, updated_at = NOW() WHERE collection_id = ?2")
                .setParameter(1, s.id()).setParameter(2, b.getCollectionId()).executeUpdate();
        }
        b.setStatus(WaIntakeBatch.EXTRACTING);   // phase 3 builds the draft collection from here
        log.info("DROPSHIP_BATCH_SUPPLIER batch={} supplier={} ({})", b.getBatchId(), s.id(), how);
        tell(b, "text", "✅ Post #" + b.getBatchId() + ": " + photos + " photo(s) from " + s.name() + " (" + how + "). "
            + "Building the collection…", null, null);
    }

    private long photoCount(WaIntakeBatch b) {
        return messages.findByBatchIdOrderByReceivedAtAscMessageIdAsc(b.getBatchId()).stream()
            .filter(m -> WaIntakeMessage.IMAGE.equals(m.getMsgType())).count();
    }

    /** Queued; srisa-agent sends it to the owner after the commit (OwnerMessageNotifier). */
    private void tell(WaIntakeBatch b, String kind, String text, String buttonText, List<Map<String, Object>> sections) {
        tell(b, kind, text, buttonText, sections, null);
    }

    /** imageUrl: sent just before the message (WhatsApp list messages can't carry an image header). */
    private void tell(WaIntakeBatch b, String kind, String text, String buttonText, List<Map<String, Object>> sections,
                      String imageUrl) {
        events.publishEvent(new OwnerMessage(b.getFromNumber(), "dropship:" + b.getBatchId() + ":" + UUID.randomUUID(),
            kind, text, buttonText, sections, imageUrl, null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Row lock + fresh state (a query would hand back the possibly stale managed instance). */
    private WaIntakeBatch lock(WaIntakeBatch b) {
        em.refresh(b, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return b;
    }

    private static Map<String, Object> row(String id, String title, String description) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("title", title.length() > 24 ? title.substring(0, 24) : title);
        if (description != null && !description.isBlank()) r.put("description", description.length() > 72 ? description.substring(0, 72) : description);
        return r;
    }

    private static Map<String, Object> result(String status, Integer batchId, Integer photoNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("batch_id", batchId);
        if (photoNo != null) m.put("photo_no", photoNo);
        return m;
    }

    static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    static String last10(String s) {
        String d = digits(s);
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private static AgentApiException bad(String message) {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, null);
    }
}
