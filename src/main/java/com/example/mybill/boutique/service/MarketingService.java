package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.*;
import com.example.mybill.boutique.entity.BoutiqueDesign;
import com.example.mybill.boutique.entity.MarketingLink;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.BoutiqueDesignRepository;
import com.example.mybill.boutique.repository.MarketingLinkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Click-to-WhatsApp ad / Instagram post attribution: marketing links (admin), resolve + conversation_started events
 * (agent) and the "Ad performance" report. Timestamps are stored in the server's zone like the rest of the boutique
 * tables; report dates are IST calendar days.
 */
@Service
public class MarketingService {

    static final ZoneId ZONE = ZoneId.systemDefault();
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String CONVERSATION_STARTED = "conversation_started";
    static final int DEFAULT_REPORT_DAYS = 30;
    static final int MAX_REPORT_DAYS = 366;

    @Autowired private MarketingLinkRepository linkRepository;
    @Autowired private BoutiqueDesignRepository designRepository;
    @Autowired private AgentCustomerService customerService;
    @PersistenceContext private EntityManager em;

    // ── Links (admin) ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MarketingLinkResponse> listLinks() {
        List<MarketingLink> links = linkRepository.findAllByOrderByActiveDescCreatedAtDescLinkIdDesc();
        Map<String, String> names = designNames();
        return links.stream().map(l -> toResponse(l, names.get(l.getTargetCode()))).toList();
    }

    @Transactional(readOnly = true)
    public List<DesignOptionResponse> designOptions() {
        return designRepository.findAll().stream()
            .filter(d -> d.getCode() != null)
            .sorted(Comparator.comparing(BoutiqueDesign::getCode))
            .map(d -> new DesignOptionResponse(d.getCode(), d.displayName(), !Boolean.FALSE.equals(d.getIsActive())))
            .toList();
    }

    @Transactional
    public MarketingLinkResponse createLink(MarketingLinkRequest request) {
        String sourceId = request.sourceId().trim();
        Target target = target(request);
        if (linkRepository.findBySourceTypeAndSourceId(request.sourceType(), sourceId).isPresent()) {
            throw duplicate(request.sourceType(), sourceId);
        }
        LocalDateTime now = LocalDateTime.now();
        MarketingLink link = new MarketingLink();
        link.setSourceType(request.sourceType());
        link.setSourceId(sourceId);
        link.setDesignCode(target.designCode());
        link.setCollectionCode(target.collectionCode());
        link.setLabel(request.label().trim());
        link.setActive(request.active() == null || request.active());
        link.setPermalink(uniquePermalink(request.sourceType(), request.permalink(), null));
        link.setCreatedBy(MarketingLink.MANUAL);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        return toResponse(linkRepository.save(link), target.name());
    }

    @Transactional
    public MarketingLinkResponse updateLink(int linkId, MarketingLinkRequest request) {
        MarketingLink link = requireLink(linkId);
        String sourceId = request.sourceId().trim();
        Target target = target(request);
        linkRepository.findBySourceTypeAndSourceId(request.sourceType(), sourceId)
            .filter(other -> !other.getLinkId().equals(link.getLinkId()))
            .ifPresent(other -> { throw duplicate(request.sourceType(), sourceId); });
        link.setSourceType(request.sourceType());
        link.setSourceId(sourceId);
        link.setDesignCode(target.designCode());
        link.setCollectionCode(target.collectionCode());
        link.setLabel(request.label().trim());
        if (request.active() != null) link.setActive(request.active());
        if (request.permalink() != null) {   // omitted (e.g. the mybill-ui form) = keep; "" = clear
            link.setPermalink(uniquePermalink(request.sourceType(), request.permalink(), link.getLinkId()));
        }
        link.setCreatedBy(MarketingLink.MANUAL);   // edited by staff: the Instagram sync leaves it alone from now on
        link.setUpdatedAt(LocalDateTime.now());
        return toResponse(linkRepository.save(link), target.name());
    }

    /** Soft delete: the link keeps its history in the report but no longer resolves for new conversations. */
    @Transactional
    public MarketingLinkResponse setActive(int linkId, boolean active) {
        MarketingLink link = requireLink(linkId);
        link.setActive(active);
        link.setUpdatedAt(LocalDateTime.now());
        return toResponse(linkRepository.save(link), designNames().get(link.getTargetCode()));
    }

    // ── Agent ────────────────────────────────────────────────────────────────

    /**
     * By source id, or (e.g. a shared Instagram post) by permalink. Only active links resolve; unknown and inactive
     * ones are both 404 (the agent treats them alike).
     */
    @Transactional(readOnly = true)
    public MarketingLinkResolveResponse resolve(String type, String id, String permalink) {
        String sourceType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!sourceType.equals(MarketingLink.AD) && !sourceType.equals(MarketingLink.IG_MEDIA)) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "type must be AD or IG_MEDIA",
                Map.of("type", String.valueOf(type)));
        }
        String sourceId = id == null ? "" : id.trim();
        String url = normalisePermalink(permalink);
        if (sourceId.isEmpty() && url == null) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "id or permalink is required", null);
        }
        Optional<MarketingLink> link = !sourceId.isEmpty()
            ? linkRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
            : linkRepository.findBySourceTypeAndPermalink(sourceType, url);
        return link.filter(MarketingLink::isActive)
            .map(l -> new MarketingLinkResolveResponse(l.getDesignCode(), l.getLabel(), l.getCollectionCode()))
            .orElseThrow(() -> AgentApiException.notFound("MARKETING_LINK_NOT_FOUND",
                "No active marketing link for " + sourceType + " " + (sourceId.isEmpty() ? url : sourceId)));
    }

    // ── Instagram sync ───────────────────────────────────────────────────────

    static final int MAX_SYNC_ITEMS = 200;
    static final int LABEL_CHARS = 60;
    private static final Pattern CODE_AFTER_LABEL = Pattern.compile("(?i)\\bcode\\s*[:\\-]\\s*([A-Z]{3}-\\d+)\\b");
    private static final Pattern ANY_CODE = Pattern.compile("\\b([A-Z]{3}-\\d+)\\b");

    /** The design code a caption is about: the one after "Code:" if present, else the first one. */
    static String designCodeIn(String caption) {
        if (caption == null) return null;
        Matcher labelled = CODE_AFTER_LABEL.matcher(caption);
        if (labelled.find()) return labelled.group(1).toUpperCase(Locale.ROOT);
        Matcher any = ANY_CODE.matcher(caption);
        return any.find() ? any.group(1) : null;
    }

    /**
     * Upserts an AUTO IG_MEDIA link per post whose caption names an existing design. Links created or edited by
     * staff (MANUAL) are never changed; deactivated AUTO links stay deactivated.
     */
    @Transactional
    public IgMediaSyncResponse syncIgMedia(List<IgMediaItem> items) {
        if (items == null || items.isEmpty()) return new IgMediaSyncResponse(0, 0, 0, List.of());
        if (items.size() > MAX_SYNC_ITEMS) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "At most " + MAX_SYNC_ITEMS + " posts per request", Map.of("count", items.size()));
        }
        List<IgMediaSyncResponse.Item> out = new ArrayList<>();
        int created = 0, updated = 0, skipped = 0;
        for (IgMediaItem item : items) {
            IgMediaSyncResponse.Item r = syncOne(item);
            out.add(r);
            switch (r.result()) {
                case "created" -> created++;
                case "updated" -> updated++;
                default -> skipped++;
            }
        }
        return new IgMediaSyncResponse(created, updated, skipped, out);
    }

    private IgMediaSyncResponse.Item syncOne(IgMediaItem item) {
        String mediaId = item == null || item.mediaId() == null ? "" : item.mediaId().trim();
        if (!mediaId.matches("[0-9]{1,40}")) return skip(mediaId, null, "invalid_media_id");
        String code = designCodeIn(item.caption());
        if (code == null) return skip(mediaId, null, "no_design_code");
        Optional<BoutiqueDesign> design = designRepository.findByCodeIgnoreCase(code);
        if (design.isEmpty()) return skip(mediaId, code, "design_not_found");
        String designCode = design.get().getCode();

        Optional<MarketingLink> existing = linkRepository.findBySourceTypeAndSourceId(MarketingLink.IG_MEDIA, mediaId);
        if (existing.isPresent() && MarketingLink.MANUAL.equals(existing.get().getCreatedBy())) {
            return skip(mediaId, designCode, "manual_link");
        }
        String url = normalisePermalink(item.permalink());
        if (url != null) {
            Optional<MarketingLink> owner = linkRepository.findBySourceTypeAndPermalink(MarketingLink.IG_MEDIA, url);
            if (owner.isPresent() && (existing.isEmpty() || !owner.get().getLinkId().equals(existing.get().getLinkId()))) {
                return skip(mediaId, designCode, "permalink_in_use");
            }
        }
        String label = captionLabel(item.caption());
        LocalDateTime now = LocalDateTime.now();
        if (existing.isEmpty()) {
            MarketingLink link = new MarketingLink();
            link.setSourceType(MarketingLink.IG_MEDIA);
            link.setSourceId(mediaId);
            link.setDesignCode(designCode);
            link.setLabel(label);
            link.setPermalink(url);
            link.setCreatedBy(MarketingLink.AUTO);
            link.setActive(true);
            link.setCreatedAt(now);
            link.setUpdatedAt(now);
            linkRepository.save(link);
            return new IgMediaSyncResponse.Item(mediaId, "created", designCode, null);
        }
        MarketingLink link = existing.get();
        String newUrl = url != null ? url : link.getPermalink();
        if (designCode.equals(link.getDesignCode()) && label.equals(link.getLabel())
                && java.util.Objects.equals(newUrl, link.getPermalink())) {
            return skip(mediaId, designCode, "unchanged");
        }
        link.setDesignCode(designCode);
        link.setLabel(label);
        link.setPermalink(newUrl);
        link.setUpdatedAt(now);
        linkRepository.save(link);
        return new IgMediaSyncResponse.Item(mediaId, "updated", designCode, null);
    }

    private static IgMediaSyncResponse.Item skip(String mediaId, String code, String reason) {
        return new IgMediaSyncResponse.Item(mediaId, "skipped", code, reason);
    }

    /** First 60 characters of the caption, whitespace collapsed (emoji kept whole). */
    static String captionLabel(String caption) {
        String text = caption == null ? "" : caption.replaceAll("\\s+", " ").trim();
        if (text.codePointCount(0, text.length()) <= LABEL_CHARS) return text;
        return text.substring(0, text.offsetByCodePoints(0, LABEL_CHARS)).trim();
    }

    /**
     * Instagram URLs compared without query/fragment, trailing slash and "www.", scheme/host lower-cased
     * (the path is case-sensitive). null for blank input.
     */
    static String normalisePermalink(String permalink) {
        if (permalink == null || permalink.isBlank()) return null;
        String url = permalink.trim();
        int cut = url.indexOf('?');
        if (cut >= 0) url = url.substring(0, cut);
        cut = url.indexOf('#');
        if (cut >= 0) url = url.substring(0, cut);
        Matcher m = Pattern.compile("(?i)^(https?)://(?:www\\.)?([^/]+)(.*)$").matcher(url);
        if (m.matches()) url = "https://" + m.group(2).toLowerCase(Locale.ROOT) + m.group(3);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.isEmpty() ? null : url;
    }

    private String uniquePermalink(String sourceType, String permalink, Integer linkId) {
        String url = normalisePermalink(permalink);
        if (url == null) return null;
        linkRepository.findBySourceTypeAndPermalink(sourceType, url)
            .filter(other -> !other.getLinkId().equals(linkId))
            .ifPresent(other -> { throw AgentApiException.conflict("PERMALINK_IN_USE",
                "Another link already uses this permalink"); });
        return url;
    }

    /** Idempotent on ctwa_clid when present: a repeat returns the first event with created = false. */
    @Transactional
    public MarketingEventResponse recordEvent(MarketingEventRequest request) {
        if (request.customerId() != null && !customerService.exists(request.customerId())) {
            throw AgentApiException.unprocessable("CUSTOMER_NOT_FOUND", "No customer with id " + request.customerId(),
                Map.of("customer_id", request.customerId()));
        }
        String clid = request.ctwaClid() == null || request.ctwaClid().isBlank() ? null : request.ctwaClid().trim();
        LocalDateTime occurredAt = request.occurredAt() == null ? LocalDateTime.now()
            : request.occurredAt().atZoneSameInstant(ZONE).toLocalDateTime();
        // Typed binding: an untyped NULL would reach PostgreSQL as bytea.
        List<?> inserted = em.createNativeQuery("""
                INSERT INTO marketing_events (event_type, source_type, source_id, ctwa_clid, customer_id, occurred_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                ON CONFLICT (ctwa_clid) DO NOTHING
                RETURNING event_id""")
            .unwrap(NativeQuery.class)
            .setParameter(1, CONVERSATION_STARTED, String.class)
            .setParameter(2, request.sourceType(), String.class)
            .setParameter(3, request.sourceId(), String.class)
            .setParameter(4, clid, String.class)
            .setParameter(5, request.customerId(), Integer.class)
            .setParameter(6, occurredAt, LocalDateTime.class)
            .getResultList();
        if (!inserted.isEmpty()) return new MarketingEventResponse(((Number) inserted.get(0)).longValue(), true);
        Number existing = (Number) em.createNativeQuery("SELECT event_id FROM marketing_events WHERE ctwa_clid = ?1")
            .setParameter(1, clid).getSingleResult();
        return new MarketingEventResponse(existing.longValue(), false);
    }

    // ── Report ───────────────────────────────────────────────────────────────

    private record Key(String sourceType, String sourceId) {}

    private static final class Counts {
        long conversations, orders, paidOrders;
        BigDecimal paidRevenue = BigDecimal.ZERO;

        void add(Counts o) {
            conversations += o.conversations; orders += o.orders; paidOrders += o.paidOrders;
            paidRevenue = paidRevenue.add(o.paidRevenue);
        }
    }

    /**
     * Per marketing link (inactive ones too) plus "Unmapped/other": ad/post sources without a link and orders that
     * did not come from an ad. PAID = paid_at set (PAID and every later status).
     */
    @Transactional(readOnly = true)
    public MarketingReport report(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(IST);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_REPORT_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "from must not be after to",
                Map.of("from", start.toString(), "to", end.toString()));
        }
        if (start.plusDays(MAX_REPORT_DAYS).isBefore(end)) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "The date range can be at most " + MAX_REPORT_DAYS + " days", null);
        }
        LocalDateTime lo = start.atStartOfDay(IST).withZoneSameInstant(ZONE).toLocalDateTime();
        LocalDateTime hi = end.plusDays(1).atStartOfDay(IST).withZoneSameInstant(ZONE).toLocalDateTime();

        Map<Key, Counts> bySource = new HashMap<>();
        for (Object[] r : rows("""
                SELECT source_type, source_id, COUNT(*)
                  FROM marketing_events
                 WHERE event_type = 'conversation_started' AND occurred_at >= ?1 AND occurred_at < ?2
                 GROUP BY source_type, source_id""", lo, hi)) {
            bySource.computeIfAbsent(new Key((String) r[0], (String) r[1]), k -> new Counts()).conversations =
                ((Number) r[2]).longValue();
        }
        for (Object[] r : rows("""
                SELECT source_type, source_id, COUNT(*),
                       COUNT(*) FILTER (WHERE paid_at IS NOT NULL),
                       COALESCE(SUM(total) FILTER (WHERE paid_at IS NOT NULL), 0)
                  FROM boutique_orders
                 WHERE created_at >= ?1 AND created_at < ?2
                 GROUP BY source_type, source_id""", lo, hi)) {
            Counts c = bySource.computeIfAbsent(new Key((String) r[0], (String) r[1]), k -> new Counts());
            c.orders = ((Number) r[2]).longValue();
            c.paidOrders = ((Number) r[3]).longValue();
            c.paidRevenue = new BigDecimal(r[4].toString());
        }

        List<MarketingLink> links = linkRepository.findAllByOrderByActiveDescCreatedAtDescLinkIdDesc();
        List<MarketingReport.Row> result = new ArrayList<>();
        Counts total = new Counts();
        for (MarketingLink l : links) {
            Counts c = Optional.ofNullable(bySource.remove(new Key(l.getSourceType(), l.getSourceId()))).orElseGet(Counts::new);
            total.add(c);
            result.add(row(l.getLinkId(), l.getSourceType(), l.getSourceId(), l.getTargetCode(), l.getLabel(), l.isActive(), c));
        }
        Counts unmapped = new Counts();
        bySource.values().forEach(unmapped::add);
        total.add(unmapped);
        result.add(row(null, null, null, null, "Unmapped/other", null, unmapped));
        return new MarketingReport(start, end, result, row(null, null, null, null, "Total", null, total));
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, LocalDateTime lo, LocalDateTime hi) {
        return em.createNativeQuery(sql).setParameter(1, lo).setParameter(2, hi).getResultList();
    }

    private static MarketingReport.Row row(Integer linkId, String type, String id, String design, String label,
                                           Boolean active, Counts c) {
        BigDecimal conversion = c.conversations == 0 ? null
            : BigDecimal.valueOf(c.paidOrders * 100L).divide(BigDecimal.valueOf(c.conversations), 1, RoundingMode.HALF_UP);
        return new MarketingReport.Row(linkId, type, id, design, label, active, c.conversations, c.orders,
            c.paidOrders, c.paidRevenue.setScale(2, RoundingMode.HALF_UP), conversion);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MarketingLink requireLink(int linkId) {
        return linkRepository.findById(linkId)
            .orElseThrow(() -> AgentApiException.notFound("MARKETING_LINK_NOT_FOUND", "No marketing link " + linkId));
    }

    private record Target(String designCode, String collectionCode, String name) {}

    /** The design or collection a link request points at (validated). */
    private Target target(MarketingLinkRequest request) {
        if (request.collectionCode() != null && !request.collectionCode().isBlank()) {
            String code = request.collectionCode().trim();
            List<?> rows = em.createNativeQuery("SELECT code, name FROM boutique_collections WHERE UPPER(code) = UPPER(?1)")
                .setParameter(1, code).getResultList();
            if (rows.isEmpty()) {
                throw AgentApiException.unprocessable("COLLECTION_NOT_FOUND", "No collection with code " + code,
                    Map.of("collection_code", code));
            }
            Object[] row = (Object[]) rows.get(0);
            return new Target(null, (String) row[0], (String) row[1]);
        }
        BoutiqueDesign design = requireDesign(request.designCode());
        return new Target(design.getCode(), null, design.displayName());
    }

    private BoutiqueDesign requireDesign(String code) {
        return designRepository.findByCodeIgnoreCase(code.trim())
            .orElseThrow(() -> AgentApiException.unprocessable("DESIGN_NOT_FOUND", "No design with code " + code,
                Map.of("design_code", code)));
    }

    private static AgentApiException duplicate(String type, String id) {
        return AgentApiException.conflict("MARKETING_LINK_EXISTS", "A link for " + type + " " + id + " already exists");
    }

    /** Display names of designs and collections by code (link targets). */
    private Map<String, String> designNames() {
        Map<String, String> names = new java.util.HashMap<>(designRepository.findAll().stream().filter(d -> d.getCode() != null)
            .collect(Collectors.toMap(BoutiqueDesign::getCode, BoutiqueDesign::displayName, (a, b) -> a)));
        for (Object r : em.createNativeQuery("SELECT code, name FROM boutique_collections").getResultList()) {
            Object[] a = (Object[]) r;
            names.putIfAbsent((String) a[0], (String) a[1]);
        }
        return names;
    }

    private static MarketingLinkResponse toResponse(MarketingLink l, String designName) {
        return new MarketingLinkResponse(l.getLinkId(), l.getSourceType(), l.getSourceId(), l.getDesignCode(),
            designName, l.getLabel(), l.isActive(), l.getPermalink(), l.getCreatedBy(), l.getCreatedAt(), l.getUpdatedAt(),
            l.getCollectionCode());
    }
}
