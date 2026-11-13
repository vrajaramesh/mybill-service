package com.example.mybill.boutique.service;

import com.example.mybill.boutique.config.BoutiqueSchemaInitializer;
import com.example.mybill.boutique.payment.TenantWork;
import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.service.GcsUploadService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Nightly dropship cleanup (Cloud Scheduler → POST /internal/jobs/dropship-cleanup), for every firm:
 * <ul>
 *   <li>Supplier forwards (wa_intake_message) of finished posts older than {@link #INTAKE}: deleted with their photos;
 *       a skipped / failed post (no collection) loses its batch row too.</li>
 *   <li>ARCHIVED dropship collections untouched for {@link #ARCHIVED} that never had an order or an Ad & post link:
 *       deleted (variants, dropship_products, its post) with every photo, and removed from the Meta catalog.
 *       Ones with orders stay; only their unused photos (collage, old stamps) go.</li>
 *   <li>Old stamped photos (replaced by a re-stamp) older than {@link #OLD_STAMPS}.</li>
 * </ul>
 * A photo is only deleted when no firm still points to it (collection items, dropship_products, covers, forwards).
 * Instagram posts are never touched. dryRun = count only, nothing is deleted.
 */
@Service
public class DropshipCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DropshipCleanupService.class);
    static final Duration INTAKE = Duration.ofDays(30);
    static final Duration ARCHIVED = Duration.ofDays(90);
    static final Duration OLD_STAMPS = Duration.ofDays(7);

    @PersistenceContext private EntityManager em;
    @Autowired private TransactionTemplate tx;
    @Autowired private TenantWork tenantWork;
    @Autowired private FirmRepository firms;
    @Autowired private BoutiqueSchemaInitializer schemaInitializer;
    @Autowired private GcsUploadService gcs;
    @Autowired private DropshipPublishService publisher;

    /** What one firm's database step found (and deleted, unless dry run). */
    private record FirmResult(int forwards, int batches, List<String> purgedCodes, List<String> catalogCodes,
                              Set<String> candidateFiles, Map<String, Boolean> collections) {}

    public Map<String, Object> run(boolean dryRun) {
        Instant now = Instant.now();
        List<String> schemas = firms.findAll().stream().map(Firm::getSchemaName).filter(s -> s != null && !s.isBlank()).toList();
        int forwards = 0, batches = 0, errors = 0;
        List<String> purged = new ArrayList<>();
        Set<String> candidates = new HashSet<>();
        Map<String, Boolean> prefixes = new LinkedHashMap<>();   // dropship/{code}/ → unused photos may go (purged / long archived)
        for (String schema : schemas) {
            try {
                schemaInitializer.ensureSchema(schema);
                FirmResult r = tenantWork.in(schema, () -> tx.execute(status -> {
                    FirmResult out = purgeDatabase(now);
                    if (dryRun) status.setRollbackOnly();
                    return out;
                }));
                forwards += r.forwards();
                batches += r.batches();
                purged.addAll(r.purgedCodes());
                candidates.addAll(r.candidateFiles());
                r.collections().forEach((code, old) -> prefixes.merge("dropship/" + code + "/", old, Boolean::logicalOr));
                if (!dryRun) publisher.catalogDelete(r.catalogCodes());
            } catch (Exception e) {
                errors++;
                log.error("Dropship cleanup failed for firm {}: {}", schema, e.toString());
            }
        }

        // photos: never one that any firm still uses
        int files = 0;
        if (errors == 0) {
            Set<String> keep = new HashSet<>();
            for (String schema : schemas) keep.addAll(tenantWork.in(schema, () -> tx.execute(s -> referencedObjects())));
            Set<String> delete = new TreeSet<>();
            candidates.stream().filter(n -> !keep.contains(n)).forEach(delete::add);
            for (var p : prefixes.entrySet()) {
                for (GcsUploadService.StoredObject o : gcs.list(p.getKey())) {
                    if (keep.contains(o.name())) continue;
                    boolean oldStamp = o.name().startsWith(p.getKey() + "stamped/") && o.created().isBefore(now.minus(OLD_STAMPS));
                    if (p.getValue() || oldStamp) delete.add(o.name());
                }
            }
            files = delete.size();
            if (!dryRun) {
                for (String name : delete) {
                    try { gcs.delete(name); } catch (Exception e) { log.warn("Photo not deleted {}: {}", name, e.toString()); }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dry_run", dryRun);
        out.put("forwards_deleted", forwards);
        out.put("posts_deleted", batches);
        out.put("collections_deleted", purged);
        out.put("photos_deleted", files);
        out.put("errors", errors);
        log.info("DROPSHIP_CLEANUP {}", out);
        return out;
    }

    /** One firm, inside its transaction. collections: every dropship code → may its unused photos go. */
    private FirmResult purgeDatabase(Instant now) {
        Set<String> files = new HashSet<>();
        List<String> purgedCodes = new ArrayList<>(), catalogCodes = new ArrayList<>();

        // 1. ARCHIVED, untouched for 90 days, never ordered, no Ad & post link
        @SuppressWarnings("unchecked")
        List<Object[]> dead = em.createNativeQuery("""
                SELECT c.collection_id, c.code FROM boutique_collections c
                 WHERE c.collection_type = 'DROPSHIP' AND c.status = 'ARCHIVED' AND c.updated_at < ?1
                   AND NOT EXISTS (SELECT 1 FROM boutique_collection_items i JOIN boutique_order_items oi
                                       ON UPPER(oi.item_code) = UPPER(i.item_code) OR UPPER(oi.design_code) = UPPER(i.item_code)
                                    WHERE i.collection_id = c.collection_id)
                   AND NOT EXISTS (SELECT 1 FROM marketing_links l WHERE l.collection_code = c.code)""")
            .setParameter(1, ts(now.minus(ARCHIVED))).getResultList();
        int forwards = 0, batches = 0;
        for (Object[] row : dead) {
            int id = ((Number) row[0]).intValue();
            purgedCodes.add((String) row[1]);
            files.addAll(urls("""
                SELECT image_url FROM boutique_collection_items WHERE collection_id = ?1
                UNION SELECT original_image_path FROM boutique_collection_items WHERE collection_id = ?1
                UNION SELECT stamped_image_url FROM boutique_collection_items WHERE collection_id = ?1
                UNION SELECT image_url FROM dropship_products WHERE collection_id = ?1
                UNION SELECT cover_image_url FROM boutique_collections WHERE collection_id = ?1
                UNION SELECT m.gcs_path FROM wa_intake_message m JOIN wa_intake_batch b ON b.batch_id = m.batch_id
                       WHERE b.collection_id = ?1""", id));
            @SuppressWarnings("unchecked")
            List<String> codes = em.createNativeQuery("SELECT item_code FROM boutique_collection_items WHERE collection_id = ?1")
                .setParameter(1, id).getResultList();
            catalogCodes.addAll(codes);
            forwards += em.createNativeQuery("""
                    DELETE FROM wa_intake_message WHERE batch_id IN (SELECT batch_id FROM wa_intake_batch WHERE collection_id = ?1)""")
                .setParameter(1, id).executeUpdate();
            batches += em.createNativeQuery("DELETE FROM wa_intake_batch WHERE collection_id = ?1").setParameter(1, id).executeUpdate();
            em.createNativeQuery("DELETE FROM boutique_collections WHERE collection_id = ?1").setParameter(1, id).executeUpdate();
            log.info("DROPSHIP_CLEANUP collection {} deleted", row[1]);
        }

        // 2. forwards of finished posts older than 30 days (the collection keeps the photos it uses)
        String finished = """
                FROM wa_intake_batch WHERE status IN ('AWAITING_APPROVAL', 'APPROVED', 'PUBLISHED', 'SKIPPED', 'FAILED')
                 AND COALESCE(closed_at, updated_at) < ?1""";
        Object cutoff = ts(now.minus(INTAKE));
        files.addAll(urls("SELECT gcs_path FROM wa_intake_message WHERE batch_id IN (SELECT batch_id " + finished + ")", cutoff));
        forwards += em.createNativeQuery("DELETE FROM wa_intake_message WHERE batch_id IN (SELECT batch_id " + finished + ")")
            .setParameter(1, cutoff).executeUpdate();
        batches += em.createNativeQuery("DELETE " + finished + " AND collection_id IS NULL").setParameter(1, cutoff).executeUpdate();

        // 3. every remaining dropship collection: old stamps always; collage etc. once archived 90 days (kept for orders)
        Map<String, Boolean> collections = new LinkedHashMap<>();
        purgedCodes.forEach(c -> collections.put(c, true));
        @SuppressWarnings("unchecked")
        List<Object[]> rest = em.createNativeQuery("""
                SELECT code, status = 'ARCHIVED' AND updated_at < ?1 FROM boutique_collections WHERE collection_type = 'DROPSHIP'""")
            .setParameter(1, ts(now.minus(ARCHIVED))).getResultList();
        rest.forEach(r -> collections.put((String) r[0], Boolean.TRUE.equals(r[1])));
        return new FirmResult(forwards, batches, purgedCodes, catalogCodes, files, collections);
    }

    /** Every photo this firm still points to (object names). */
    private Set<String> referencedObjects() {
        return urls("""
            SELECT image_url FROM boutique_collection_items
            UNION SELECT original_image_path FROM boutique_collection_items
            UNION SELECT stamped_image_url FROM boutique_collection_items
            UNION SELECT image_url FROM dropship_products
            UNION SELECT cover_image_url FROM boutique_collections
            UNION SELECT gcs_path FROM wa_intake_message""", null);
    }

    private Set<String> urls(String sql, Object param) {
        var q = em.createNativeQuery(sql);
        if (param != null) q.setParameter(1, param);
        Set<String> out = new HashSet<>();
        for (Object url : q.getResultList()) {
            String name = url == null ? null : gcs.objectName(url.toString());
            if (name != null) out.add(name);
        }
        return out;
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }
}
