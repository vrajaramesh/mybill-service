package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.GoodsLine;
import com.example.mybill.boutique.dto.GoodsLineResponse;
import com.example.mybill.boutique.dto.PriceBreakdown;
import com.example.mybill.boutique.entity.BoutiqueOrder;
import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Goods carts (fabric by the metre, readymade garments by size) priced and checked against live Products data:
 * the product must be active and online; fabric: at least 1 m, steps of 0.25 m, total per product within stock;
 * readymade: whole pieces of a size the product has, total per size within that size's stock.
 * Prices are the products' selling prices; delivery as for every order (PricingService).
 */
@Service
public class GoodsService {

    public static final String FABRIC = "FABRIC", READYMADE = "READYMADE";
    static final BigDecimal MIN_METRES = BigDecimal.ONE;

    @PersistenceContext private EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private PricingService pricing;

    public record Priced(List<GoodsLineResponse> lines, PriceBreakdown breakdown, String orderType) {}

    private record Product(int id, String name, String unit, BigDecimal price, BigDecimal stock, boolean available) {}

    @Transactional(readOnly = true)
    public Priced price(List<GoodsLine> lines) {
        Map<String, Object[]> items = new HashMap<>();          // ITEM CODE -> {product_id, colour, item_code}
        Set<String> codes = new HashSet<>();
        for (GoodsLine l : lines) if (l.itemCode() != null && !l.itemCode().isBlank()) codes.add(l.itemCode().trim().toUpperCase(Locale.ROOT));
        if (!codes.isEmpty()) {
            for (Object r : em.createNativeQuery("SELECT UPPER(item_code), product_id, colour, item_code FROM boutique_collection_items WHERE UPPER(item_code) IN (?1)")
                    .setParameter(1, codes).getResultList()) {
                Object[] a = (Object[]) r;
                items.put((String) a[0], new Object[]{((Number) a[1]).intValue(), a[2], a[3]});
            }
        }
        Set<Integer> productIds = new HashSet<>();
        for (GoodsLine l : lines) {
            if (l.itemCode() != null && !l.itemCode().isBlank()) {
                Object[] item = items.get(l.itemCode().trim().toUpperCase(Locale.ROOT));
                if (item == null) throw err("ITEM_NOT_FOUND", "No item with code " + l.itemCode(), Map.of("item_code", l.itemCode()));
                productIds.add((Integer) item[0]);
            } else {
                productIds.add(l.productId());
            }
        }
        Map<Integer, Product> products = products(productIds);
        Map<Integer, Map<String, Object[]>> sizes = sizes(productIds);   // product -> SIZE -> {label, stock}

        Map<Integer, BigDecimal> metresUsed = new HashMap<>();
        Map<String, BigDecimal> piecesUsed = new HashMap<>();             // "product|SIZE" -> pieces
        List<GoodsLineResponse> out = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        for (GoodsLine l : lines) {
            boolean fromItem = l.itemCode() != null && !l.itemCode().isBlank();
            Object[] item = fromItem ? items.get(l.itemCode().trim().toUpperCase(Locale.ROOT)) : null;
            int productId = item != null ? (Integer) item[0] : l.productId();
            String code = item != null ? (String) item[2] : null;
            String colour = item != null ? (String) item[1] : null;
            String label = code != null ? code : "product " + productId;
            Product p = products.get(productId);
            if (p == null || !p.available()) {
                throw err("PRODUCT_UNAVAILABLE", label + " is not available right now", Map.of("code", label));
            }
            BigDecimal qty = l.quantity();
            String kind, size = null;
            if (CollectionService.isMetres(p.unit())) {
                kind = FABRIC;
                if (qty.compareTo(MIN_METRES) < 0 || qty.multiply(BigDecimal.valueOf(4)).stripTrailingZeros().scale() > 0) {
                    throw err("INVALID_METRES", "Fabric is sold from 1 m, in steps of 0.25 m", Map.of("code", label, "metres", qty));
                }
                BigDecimal used = metresUsed.merge(productId, qty, BigDecimal::add);
                if (used.compareTo(p.stock()) > 0) {
                    throw err("INSUFFICIENT_STOCK", "Only " + plain(p.stock()) + " m of " + label + " in stock",
                        Map.of("code", label, "available", p.stock()));
                }
            } else if (CollectionService.isPieces(p.unit())) {
                kind = READYMADE;
                if (qty.stripTrailingZeros().scale() > 0) {
                    throw err("INVALID_QUANTITY", "Readymade items are ordered in whole pieces", Map.of("code", label));
                }
                Map<String, Object[]> productSizes = sizes.getOrDefault(productId, Map.of());
                BigDecimal available;
                if (!productSizes.isEmpty()) {
                    List<String> inStock = productSizes.values().stream()
                        .filter(s -> ((BigDecimal) s[1]).signum() > 0).map(s -> (String) s[0]).toList();
                    if (l.size() == null || l.size().isBlank()) {
                        throw err("SIZE_REQUIRED", "Which size of " + label + "?", Map.of("code", label, "sizes_in_stock", inStock));
                    }
                    Object[] s = productSizes.get(l.size().trim().toUpperCase(Locale.ROOT));
                    if (s == null) {
                        throw err("SIZE_NOT_AVAILABLE", label + " doesn't come in size " + l.size(),
                            Map.of("code", label, "sizes_in_stock", inStock));
                    }
                    size = (String) s[0];
                    available = (BigDecimal) s[1];
                } else {
                    size = l.size() == null || l.size().isBlank() ? null : l.size().trim();
                    available = p.stock();
                }
                BigDecimal used = piecesUsed.merge(productId + "|" + (size == null ? "" : size.toUpperCase(Locale.ROOT)), qty, BigDecimal::add);
                if (used.compareTo(available) > 0) {
                    throw err("INSUFFICIENT_STOCK", "Only " + plain(available) + " piece(s) of " + label
                        + (size != null ? " in size " + size : "") + " in stock",
                        Map.of("code", label, "size", size == null ? "" : size, "available", available));
                }
            } else {
                throw err("UNSUPPORTED_UNIT", label + " can't be ordered here (unit " + p.unit() + ")", Map.of("code", label));
            }
            kinds.add(kind);
            BigDecimal lineTotal = PricingService.money(p.price().multiply(qty));
            out.add(new GoodsLineResponse(code, productId, p.name(), colour, kind, FABRIC.equals(kind) ? "m" : "piece",
                size, qty.stripTrailingZeros(), PricingService.money(p.price()), lineTotal));
        }
        BigDecimal subtotal = out.stream().map(GoodsLineResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        String type = kinds.size() > 1 ? BoutiqueOrder.TYPE_MIXED : (kinds.contains(FABRIC) ? BoutiqueOrder.TYPE_FABRIC : BoutiqueOrder.TYPE_READYMADE);
        return new Priced(out, pricing.priceSubtotal(subtotal), type);
    }

    private Map<Integer, Product> products(Set<Integer> ids) {
        Map<Integer, Product> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object r : em.createNativeQuery("""
                SELECT product_id, product_name, unit, selling_price, stock_quantity, is_active, is_online
                  FROM products WHERE product_id IN (?1)""").setParameter(1, ids).getResultList()) {
            Object[] a = (Object[]) r;
            BigDecimal price = a[3] == null ? BigDecimal.ZERO : new BigDecimal(a[3].toString());
            out.put(((Number) a[0]).intValue(), new Product(((Number) a[0]).intValue(), (String) a[1], (String) a[2], price,
                a[4] == null ? BigDecimal.ZERO : new BigDecimal(a[4].toString()),
                Boolean.TRUE.equals(a[5]) && !Boolean.FALSE.equals(a[6]) && price.signum() > 0));
        }
        return out;
    }

    private Map<Integer, Map<String, Object[]>> sizes(Set<Integer> ids) {
        Map<Integer, Map<String, Object[]>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object r : em.createNativeQuery("SELECT product_id, size_label, stock FROM product_size_stock WHERE product_id IN (?1) ORDER BY sort_order")
                .setParameter(1, ids).getResultList()) {
            Object[] a = (Object[]) r;
            out.computeIfAbsent(((Number) a[0]).intValue(), k -> new LinkedHashMap<>())
                .put(((String) a[1]).toUpperCase(Locale.ROOT), new Object[]{a[1], new BigDecimal(a[2].toString())});
        }
        return out;
    }

    /**
     * Take a paid goods order's lines out of stock (once per order): metres or pieces from products.stock_quantity,
     * and pieces from the size's row (the product's total is then the sum of its sizes). Never below zero.
     */
    @Transactional
    public void reduceStock(BoutiqueOrder order) {
        for (var item : order.getItems()) {
            Integer productId = item.getFabricProductId();
            if (productId == null) continue;
            BigDecimal qty = item.getFabricMetres() != null ? item.getFabricMetres() : BigDecimal.valueOf(item.getQuantity());
            String size = item.getSizeCode();
            int sized = 0;
            if (READYMADE.equals(item.getItemKind()) && size != null && !size.isBlank()) {
                sized = em.createNativeQuery("""
                        UPDATE product_size_stock SET stock = GREATEST(stock - ?1, 0)
                         WHERE product_id = ?2 AND UPPER(size_label) = UPPER(?3)""")
                    .setParameter(1, qty).setParameter(2, productId).setParameter(3, size).executeUpdate();
            }
            if (sized > 0) {
                em.createNativeQuery("""
                        UPDATE products SET stock_quantity = (SELECT COALESCE(SUM(stock), 0) FROM product_size_stock WHERE product_id = ?1),
                               updated_at = NOW() WHERE product_id = ?1""").setParameter(1, productId).executeUpdate();
            } else {
                em.createNativeQuery("UPDATE products SET stock_quantity = GREATEST(stock_quantity - ?1, 0), updated_at = NOW() WHERE product_id = ?2")
                    .setParameter(1, qty).setParameter(2, productId).executeUpdate();
            }
        }
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private static AgentApiException err(String code, String message, Map<String, Object> details) {
        return AgentApiException.unprocessable(code, message, details);
    }
}
