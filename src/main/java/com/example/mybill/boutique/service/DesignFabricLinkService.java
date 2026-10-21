package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.FabricLinkDto;
import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Website links for fabrics sold separately with a design (boutique_design_fabrics). Links must point at the
 * shop website (boutique.shop.base-url, e.g. https://srisafabrics.com); ?product=&lt;id&gt; names the product.
 */
@Service
public class DesignFabricLinkService {

    public static final int MAX_LINKS = 10;

    @PersistenceContext private EntityManager em;

    private final String baseUrl;
    private final String host;

    public DesignFabricLinkService(@Value("${boutique.shop.base-url:https://srisafabrics.com}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.host = URI.create(this.baseUrl).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    }

    /** The website page for a product, e.g. https://srisafabrics.com/?product=6223. */
    public String productUrl(int productId) {
        return baseUrl + "/?product=" + productId;
    }

    /** product id from a shop link's ?product= parameter (null if absent). */
    public static Integer productIdOf(String url) {
        try {
            String query = URI.create(url.trim()).getRawQuery();
            if (query == null) return null;
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && kv[0].equals("product") && kv[1].matches("\\d{1,9}")) return Integer.valueOf(kv[1]);
            }
        } catch (IllegalArgumentException ignored) {
            // not a URL
        }
        return null;
    }

    /** Normalised https link on the shop's host, or a 400 explaining what is wrong. */
    public String validate(String url) {
        String u = url == null ? "" : url.trim();
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            throw bad("Not a valid link: " + u);
        }
        String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !h.equals(host)) {
            throw bad("Links must be product pages on " + baseUrl + " (got " + u + ")");
        }
        if (u.length() > 500) throw bad("Link too long");
        return u;
    }

    @Transactional(readOnly = true)
    public Map<Integer, List<FabricLinkDto>> forDesigns(List<Integer> designIds) {
        Map<Integer, List<FabricLinkDto>> out = new LinkedHashMap<>();
        if (designIds.isEmpty()) return out;
        List<?> rows = em.createNativeQuery("""
                SELECT f.design_id, f.id, f.url, f.product_id, f.label, p.product_name, p.selling_price, p.unit,
                       p.stock_quantity, p.is_active, p.is_online,
                       (SELECT i.image_url FROM product_images i
                         WHERE i.product_id = f.product_id AND COALESCE(i.media_type, 'image') = 'image'
                         ORDER BY COALESCE(i.sort_order, 0), i.image_id LIMIT 1) AS image_url
                  FROM boutique_design_fabrics f
                  LEFT JOIN products p ON p.product_id = f.product_id
                 WHERE f.design_id IN (?1)
                 ORDER BY f.design_id, f.sort_order, f.id""")
            .setParameter(1, designIds).getResultList();
        for (Object r : rows) {
            Object[] a = (Object[]) r;
            BigDecimal stock = a[8] == null ? null : new BigDecimal(a[8].toString());
            boolean exists = a[5] != null;
            out.computeIfAbsent(((Number) a[0]).intValue(), k -> new ArrayList<>()).add(new FabricLinkDto(
                ((Number) a[1]).intValue(), (String) a[2], a[3] == null ? null : ((Number) a[3]).intValue(), (String) a[4],
                (String) a[5], a[6] == null ? null : PricingService.money(new BigDecimal(a[6].toString())), (String) a[7],
                exists ? stock != null && stock.signum() > 0 : null,
                exists ? Boolean.TRUE.equals(a[9]) && Boolean.TRUE.equals(a[10]) : null, (String) a[11]));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<FabricLinkDto> forDesign(int designId) {
        return forDesigns(List.of(designId)).getOrDefault(designId, List.of());
    }

    /** Replace a design's links (in the given order). Each item: {url, label?}. */
    @Transactional
    public List<FabricLinkDto> replace(int designId, List<Map<String, Object>> links) {
        if (links.size() > MAX_LINKS) throw bad("At most " + MAX_LINKS + " fabric links per design");
        Set<String> seen = new LinkedHashSet<>();
        em.createNativeQuery("DELETE FROM boutique_design_fabrics WHERE design_id = ?1").setParameter(1, designId).executeUpdate();
        int order = 0;
        for (Map<String, Object> link : links) {
            String url = validate(link.get("url") instanceof String s ? s : null);
            if (!seen.add(url)) continue;
            String label = link.get("label") instanceof String s && !s.isBlank() ? s.trim() : null;
            em.createNativeQuery("""
                    INSERT INTO boutique_design_fabrics (design_id, url, product_id, label, sort_order)
                    VALUES (?1, ?2, ?3, ?4, ?5)""")
                .unwrap(NativeQuery.class)
                .setParameter(1, designId, Integer.class)
                .setParameter(2, url, String.class)
                .setParameter(3, productIdOf(url), Integer.class)
                .setParameter(4, label == null ? null : label.substring(0, Math.min(label.length(), 150)), String.class)
                .setParameter(5, order++, Integer.class)
                .executeUpdate();
        }
        return forDesign(designId);
    }

    private static AgentApiException bad(String message) {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "INVALID_FABRIC_LINK", message, Map.of());
    }
}
