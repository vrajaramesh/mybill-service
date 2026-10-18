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
        return links.stream().map(l -> toResponse(l, names.get(l.getDesignCode()))).toList();
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
        BoutiqueDesign design = requireDesign(request.designCode());
        if (linkRepository.findBySourceTypeAndSourceId(request.sourceType(), sourceId).isPresent()) {
            throw duplicate(request.sourceType(), sourceId);
        }
        LocalDateTime now = LocalDateTime.now();
        MarketingLink link = new MarketingLink();
        link.setSourceType(request.sourceType());
        link.setSourceId(sourceId);
        link.setDesignCode(design.getCode());
        link.setLabel(request.label().trim());
        link.setActive(request.active() == null || request.active());
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        return toResponse(linkRepository.save(link), design.displayName());
    }

    @Transactional
    public MarketingLinkResponse updateLink(int linkId, MarketingLinkRequest request) {
        MarketingLink link = requireLink(linkId);
        String sourceId = request.sourceId().trim();
        BoutiqueDesign design = requireDesign(request.designCode());
        linkRepository.findBySourceTypeAndSourceId(request.sourceType(), sourceId)
            .filter(other -> !other.getLinkId().equals(link.getLinkId()))
            .ifPresent(other -> { throw duplicate(request.sourceType(), sourceId); });
        link.setSourceType(request.sourceType());
        link.setSourceId(sourceId);
        link.setDesignCode(design.getCode());
        link.setLabel(request.label().trim());
        if (request.active() != null) link.setActive(request.active());
        link.setUpdatedAt(LocalDateTime.now());
        return toResponse(linkRepository.save(link), design.displayName());
    }

    /** Soft delete: the link keeps its history in the report but no longer resolves for new conversations. */
    @Transactional
    public MarketingLinkResponse setActive(int linkId, boolean active) {
        MarketingLink link = requireLink(linkId);
        link.setActive(active);
        link.setUpdatedAt(LocalDateTime.now());
        return toResponse(linkRepository.save(link), designNames().get(link.getDesignCode()));
    }

    // ── Agent ────────────────────────────────────────────────────────────────

    /** Only active links resolve; unknown and inactive ones are both 404 (the agent treats them alike). */
    @Transactional(readOnly = true)
    public MarketingLinkResolveResponse resolve(String type, String id) {
        String sourceType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        String sourceId = id == null ? "" : id.trim();
        if (!sourceType.equals(MarketingLink.AD) && !sourceType.equals(MarketingLink.IG_MEDIA)) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "type must be AD or IG_MEDIA",
                Map.of("type", String.valueOf(type)));
        }
        return linkRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
            .filter(MarketingLink::isActive)
            .map(l -> new MarketingLinkResolveResponse(l.getDesignCode(), l.getLabel()))
            .orElseThrow(() -> AgentApiException.notFound("MARKETING_LINK_NOT_FOUND",
                "No active marketing link for " + sourceType + " " + sourceId));
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
            result.add(row(l.getLinkId(), l.getSourceType(), l.getSourceId(), l.getDesignCode(), l.getLabel(), l.isActive(), c));
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

    private BoutiqueDesign requireDesign(String code) {
        return designRepository.findByCodeIgnoreCase(code.trim())
            .orElseThrow(() -> AgentApiException.unprocessable("DESIGN_NOT_FOUND", "No design with code " + code,
                Map.of("design_code", code)));
    }

    private static AgentApiException duplicate(String type, String id) {
        return AgentApiException.conflict("MARKETING_LINK_EXISTS", "A link for " + type + " " + id + " already exists");
    }

    private Map<String, String> designNames() {
        return designRepository.findAll().stream().filter(d -> d.getCode() != null)
            .collect(Collectors.toMap(BoutiqueDesign::getCode, BoutiqueDesign::displayName, (a, b) -> a));
    }

    private static MarketingLinkResponse toResponse(MarketingLink l, String designName) {
        return new MarketingLinkResponse(l.getLinkId(), l.getSourceType(), l.getSourceId(), l.getDesignCode(),
            designName, l.getLabel(), l.isActive(), l.getCreatedAt(), l.getUpdatedAt());
    }
}
