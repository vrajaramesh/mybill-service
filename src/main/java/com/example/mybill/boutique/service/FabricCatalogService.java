package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.FabricResponse;
import com.example.mybill.boutique.dto.ImageDto;
import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fabrics sold by the metre through the agent: rows of the shop's products table that are active, online and
 * measured in metres (unit Meters / MTS / MTR ...). Read-only; stock is checked, never changed here.
 */
@Service
public class FabricCatalogService {

    public static final String CODE_PREFIX = "FAB-";
    static final int MAX_SEARCH = 10;
    private static final int MAX_IMAGES = 5;
    private static final Pattern CODE = Pattern.compile("^(?i)(?:FAB-?)?(\\d{1,9})$");

    /** Columns: product_id, product_name, category, description, selling_price, stock_quantity. */
    private static final String SELECT = """
        SELECT p.product_id, p.product_name, p.category, p.description, p.selling_price, p.stock_quantity
          FROM products p
         WHERE p.is_active = TRUE AND COALESCE(p.is_online, FALSE) = TRUE AND p.selling_price > 0
           AND LOWER(TRIM(COALESCE(p.unit, ''))) IN ('m', 'meter', 'meters', 'metre', 'metres', 'mts', 'mtr', 'mtrs')
        """;

    @PersistenceContext private EntityManager em;

    /** product id from "FAB-6223", "fab6223" or "6223"; null if it isn't a fabric code. */
    public static Integer parseCode(String code) {
        if (code == null) return null;
        Matcher m = CODE.matcher(code.trim());
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    /** In-stock fabrics matching every word of the query (name, category, tags, description), best stocked first. */
    @Transactional(readOnly = true)
    public List<FabricResponse> search(String query, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_SEARCH));
        Integer id = parseCode(query);
        if (id != null) {
            return find(id).filter(f -> f.availableMetres().signum() > 0).map(List::of).orElse(List.of());
        }
        List<String> words = new ArrayList<>();
        for (String w : (query == null ? "" : query).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2 && words.size() < 5) words.add(w);
        }
        StringBuilder sql = new StringBuilder(SELECT).append(" AND p.stock_quantity >= 1");
        for (int i = 0; i < words.size(); i++) {
            sql.append(" AND LOWER(CONCAT_WS(' ', p.product_name, p.category, p.tags, p.description)) LIKE ?").append(i + 1);
        }
        sql.append(" ORDER BY p.stock_quantity DESC, p.product_id DESC LIMIT ").append(size);
        NativeQuery<?> q = em.createNativeQuery(sql.toString()).unwrap(NativeQuery.class);
        for (int i = 0; i < words.size(); i++) q.setParameter(i + 1, "%" + words.get(i) + "%", String.class);
        List<?> rows = q.getResultList();
        Map<Integer, List<ImageDto>> images = images(rows.stream().map(r -> ((Number) ((Object[]) r)[0]).intValue()).toList());
        return rows.stream().map(r -> toResponse((Object[]) r, images)).toList();
    }

    /** A fabric by product id (also when out of stock); empty if it isn't an online metre fabric. */
    @Transactional(readOnly = true)
    public java.util.Optional<FabricResponse> find(int productId) {
        List<?> rows = em.createNativeQuery(SELECT + " AND p.product_id = ?1").setParameter(1, productId).getResultList();
        if (rows.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(toResponse((Object[]) rows.get(0), images(List.of(productId))));
    }

    @Transactional(readOnly = true)
    public FabricResponse require(int productId) {
        return find(productId).orElseThrow(() -> AgentApiException.notFound("FABRIC_NOT_FOUND",
            "No fabric " + CODE_PREFIX + productId + " for sale by the metre"));
    }

    private Map<Integer, List<ImageDto>> images(List<Integer> productIds) {
        Map<Integer, List<ImageDto>> out = new LinkedHashMap<>();
        if (productIds.isEmpty()) return out;
        List<?> rows = em.createNativeQuery("""
                SELECT product_id, image_url, sort_order FROM product_images
                 WHERE product_id IN (?1) AND COALESCE(media_type, 'image') = 'image' AND image_url LIKE 'https://%'
                 ORDER BY product_id, COALESCE(sort_order, 0), image_id""")
            .setParameter(1, productIds).getResultList();
        for (Object r : rows) {
            Object[] a = (Object[]) r;
            List<ImageDto> list = out.computeIfAbsent(((Number) a[0]).intValue(), k -> new ArrayList<>());
            if (list.size() < MAX_IMAGES) list.add(new ImageDto((String) a[1], a[2] == null ? 0 : ((Number) a[2]).intValue()));
        }
        return out;
    }

    private static FabricResponse toResponse(Object[] a, Map<Integer, List<ImageDto>> images) {
        int id = ((Number) a[0]).intValue();
        BigDecimal stock = a[5] == null ? BigDecimal.ZERO : new BigDecimal(a[5].toString());
        return new FabricResponse(id, CODE_PREFIX + id, (String) a[1], (String) a[2], (String) a[3],
            PricingService.money(new BigDecimal(a[4].toString())), stock.max(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.DOWN),
            PricingService.CURRENCY, images.getOrDefault(id, List.of()));
    }
}
