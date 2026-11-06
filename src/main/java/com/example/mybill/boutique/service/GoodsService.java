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
 * Dropship items (a DROPSHIP collection, V16): no stock check (the supplier holds it), whole pieces of a size the product
 * lists; a dropship cart is one supplier's items only, and its delivery charge is that supplier's courier slab for the
 * zone to the pincode (DropshipService) - never free.
 */
@Service
public class GoodsService {

    public static final String FABRIC = "FABRIC", READYMADE = "READYMADE", DROPSHIP = "DROPSHIP", WHOLESALE = "WHOLESALE";
    static final BigDecimal MIN_METRES = BigDecimal.ONE;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GoodsService.class);

    @PersistenceContext private EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private PricingService pricing;
    @org.springframework.beans.factory.annotation.Autowired private DropshipService dropshipService;
    @org.springframework.beans.factory.annotation.Autowired private WholesaleCollectionService wholesaleCollections;

    /** dropship is set for a dropship cart (order_type DROPSHIP), wholesale for a wholesale cart (WHOLESALE). */
    public record Priced(List<GoodsLineResponse> lines, PriceBreakdown breakdown, String orderType, Dropship dropship,
                         WholesaleCollectionService.Totals wholesale) {}

    /** What a dropship cart owes the supplier: cost = products' cost price x qty; courier = their slab (zone null = no pincode yet). */
    public record Dropship(int supplierId, String supplierName, int dispatchDays, BigDecimal cost, BigDecimal courier, String zone) {}

    private record Product(int id, String name, String unit, BigDecimal price, BigDecimal stock, boolean available, BigDecimal cost) {}

    /** A DROPSHIP collection behind an item code. */
    private record DropshipSource(String collectionCode, Integer supplierId, String slabs, BigDecimal extraPerPiece) {}

    @Transactional(readOnly = true)
    public Priced price(List<GoodsLine> lines) {
        return price(lines, null);
    }

    /** pincode (may be null) only matters for dropship carts: the supplier's courier charge depends on the zone. */
    @Transactional(readOnly = true)
    public Priced price(List<GoodsLine> lines, String pincode) {
        Map<String, Object[]> items = new HashMap<>();          // ITEM CODE -> {product_id, colour, item_code, DropshipSource}
        Map<String, Object[]> wholesaleItems = new HashMap<>(); // ITEM CODE -> {wholesale_product_id, colour, item_code, collection code}
        Set<String> codes = new HashSet<>();
        for (GoodsLine l : lines) if (l.itemCode() != null && !l.itemCode().isBlank()) codes.add(l.itemCode().trim().toUpperCase(Locale.ROOT));
        if (!codes.isEmpty()) {
            for (Object r : em.createNativeQuery("""
                    SELECT UPPER(i.item_code), i.product_id, i.colour, i.item_code, c.collection_type, c.code, c.supplier_id,
                           c.courier_slabs, c.courier_extra_per_piece, i.wholesale_product_id
                      FROM boutique_collection_items i JOIN boutique_collections c ON c.collection_id = i.collection_id
                     WHERE UPPER(i.item_code) IN (?1)""")
                    .setParameter(1, codes).getResultList()) {
                Object[] a = (Object[]) r;
                if (WHOLESALE.equals(a[4])) {
                    if (a[9] != null) wholesaleItems.put((String) a[0], new Object[]{((Number) a[9]).intValue(), a[2], a[3], a[5]});
                    continue;
                }
                if (a[1] == null) continue;
                DropshipSource ds = DROPSHIP.equals(a[4]) ? new DropshipSource((String) a[5],
                    a[6] == null ? null : ((Number) a[6]).intValue(), (String) a[7],
                    a[8] == null ? null : new BigDecimal(a[8].toString())) : null;
                items.put((String) a[0], new Object[]{((Number) a[1]).intValue(), a[2], a[3], ds});
            }
        }
        if (!wholesaleItems.isEmpty()) return wholesale(lines, wholesaleItems);
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
        List<DropshipSource> dropshipSources = new ArrayList<>();
        BigDecimal dropshipCost = BigDecimal.ZERO;
        int dropshipPieces = 0;
        for (GoodsLine l : lines) {
            boolean fromItem = l.itemCode() != null && !l.itemCode().isBlank();
            Object[] item = fromItem ? items.get(l.itemCode().trim().toUpperCase(Locale.ROOT)) : null;
            int productId = item != null ? (Integer) item[0] : l.productId();
            String code = item != null ? (String) item[2] : null;
            String colour = item != null ? (String) item[1] : null;
            DropshipSource ds = item != null ? (DropshipSource) item[3] : null;
            String label = code != null ? code : "product " + productId;
            Product p = products.get(productId);
            if (p == null || !p.available()) {
                throw err("PRODUCT_UNAVAILABLE", label + " is not available right now", Map.of("code", label));
            }
            BigDecimal qty = l.quantity();
            String kind, size = null;
            if (ds != null) {
                kind = DROPSHIP;
                if (qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 0) {
                    throw err("INVALID_QUANTITY", label + " is ordered in whole pieces", Map.of("code", label));
                }
                Map<String, Object[]> productSizes = sizes.getOrDefault(productId, Map.of());
                if (!productSizes.isEmpty()) {
                    List<String> all = productSizes.values().stream().map(s -> (String) s[0]).toList();
                    if (l.size() == null || l.size().isBlank()) {
                        throw err("SIZE_REQUIRED", "Which size of " + label + "?", Map.of("code", label, "sizes_in_stock", all));
                    }
                    Object[] s = productSizes.get(l.size().trim().toUpperCase(Locale.ROOT));
                    if (s == null) {
                        throw err("SIZE_NOT_AVAILABLE", label + " doesn't come in size " + l.size(), Map.of("code", label, "sizes_in_stock", all));
                    }
                    size = (String) s[0];
                } else {
                    size = l.size() == null || l.size().isBlank() ? null : l.size().trim();
                }
                dropshipSources.add(ds);
                dropshipPieces += qty.intValueExact();
                if (p.cost() == null) log.warn("DROPSHIP_COST_MISSING product={} item={} (supplier cost counted as 0)", productId, label);
                else dropshipCost = dropshipCost.add(p.cost().multiply(qty));
            } else if (CollectionService.isMetres(p.unit())) {
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
        if (kinds.contains(DROPSHIP)) {
            return dropship(out, subtotal, kinds, dropshipSources, dropshipCost, dropshipPieces, pincode);
        }
        String type = kinds.size() > 1 ? BoutiqueOrder.TYPE_MIXED : (kinds.contains(FABRIC) ? BoutiqueOrder.TYPE_FABRIC : BoutiqueOrder.TYPE_READYMADE);
        return new Priced(out, pricing.priceSubtotal(subtotal), type, null, null);
    }

    /** A wholesale cart: wholesale collection items only (priced by the Wholesale module's slabs). */
    private Priced wholesale(List<GoodsLine> lines, Map<String, Object[]> wholesaleItems) {
        List<WholesaleCollectionService.Line> out = new ArrayList<>();
        for (GoodsLine l : lines) {
            Object[] item = l.itemCode() == null ? null : wholesaleItems.get(l.itemCode().trim().toUpperCase(Locale.ROOT));
            if (item == null) {
                throw err("WHOLESALE_SEPARATE_ORDER", "Wholesale items are ordered separately from other items",
                    Map.of("hint", "Finish the wholesale order first, then order the other items separately."));
            }
            out.add(new WholesaleCollectionService.Line((String) item[2], (String) item[1], (String) item[3], (Integer) item[0], l.quantity()));
        }
        return wholesaleCollections.price(out);
    }

    /** A dropship cart: one supplier only; delivery = the highest slab of its collections for the zone + extra pieces. */
    private Priced dropship(List<GoodsLineResponse> out, BigDecimal subtotal, Set<String> kinds, List<DropshipSource> sources,
                            BigDecimal cost, int pieces, String pincode) {
        if (kinds.size() > 1) {
            throw err("DROPSHIP_SEPARATE_ORDER", "This item ships separately from our other items - please order it on its own",
                Map.of("hint", "Place the other items as a second order after this one."));
        }
        Set<Integer> suppliers = new HashSet<>();
        sources.forEach(s -> suppliers.add(s.supplierId()));
        if (suppliers.size() > 1) {
            throw err("DROPSHIP_SEPARATE_ORDER", "These items ship from different places - please order them one at a time",
                Map.of("hint", "Order one item now and the other as a second order."));
        }
        Integer supplierId = suppliers.iterator().next();
        DropshipService.Supplier supplier = supplierId == null ? null : dropshipService.supplier(supplierId).filter(DropshipService.Supplier::active).orElse(null);
        if (supplier == null) {
            log.warn("DROPSHIP_NOT_READY collection={} (no active supplier)", sources.get(0).collectionCode());
            throw err("PRODUCT_UNAVAILABLE", sources.get(0).collectionCode() + " is not available right now", Map.of("code", sources.get(0).collectionCode()));
        }
        String zone = pincode == null || pincode.isBlank() ? null : DropshipService.zone(supplier.pincode(), pincode);
        BigDecimal courier = null, extra = BigDecimal.ZERO;
        for (DropshipSource s : sources) {
            DropshipService.Slabs slabs = dropshipService.parseSlabs(s.slabs(), s.extraPerPiece());
            BigDecimal c = DropshipService.slabFor(slabs, zone).orElse(null);
            if (c == null) {
                log.warn("DROPSHIP_NOT_READY collection={} (no courier charges)", s.collectionCode());
                throw err("PRODUCT_UNAVAILABLE", s.collectionCode() + " is not available right now", Map.of("code", s.collectionCode()));
            }
            if (courier == null || c.compareTo(courier) > 0) courier = c;
            if (slabs.extraPerPiece().compareTo(extra) > 0) extra = slabs.extraPerPiece();
        }
        courier = PricingService.money(courier.add(extra.multiply(BigDecimal.valueOf(Math.max(pieces - 1, 0)))));
        BigDecimal st = PricingService.money(subtotal);
        PriceBreakdown b = new PriceBreakdown(st, List.of(), PricingService.money(BigDecimal.ZERO), st, 1, st, courier,
            null, PricingService.money(st.add(courier)), PricingService.CURRENCY);
        return new Priced(out, b, BoutiqueOrder.TYPE_DROPSHIP,
            new Dropship(supplier.id(), supplier.name(), supplier.dispatchDays(), PricingService.money(cost), courier, zone), null);
    }

    private Map<Integer, Product> products(Set<Integer> ids) {
        Map<Integer, Product> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object r : em.createNativeQuery("""
                SELECT product_id, product_name, unit, selling_price, stock_quantity, is_active, is_online, cost_price
                  FROM products WHERE product_id IN (?1)""").setParameter(1, ids).getResultList()) {
            Object[] a = (Object[]) r;
            BigDecimal price = a[3] == null ? BigDecimal.ZERO : new BigDecimal(a[3].toString());
            out.put(((Number) a[0]).intValue(), new Product(((Number) a[0]).intValue(), (String) a[1], (String) a[2], price,
                a[4] == null ? BigDecimal.ZERO : new BigDecimal(a[4].toString()),
                Boolean.TRUE.equals(a[5]) && !Boolean.FALSE.equals(a[6]) && price.signum() > 0,
                a[7] == null ? null : new BigDecimal(a[7].toString())));
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
            if (productId == null || DROPSHIP.equals(item.getItemKind()) || WHOLESALE.equals(item.getItemKind())) continue;   // supplier / wholesale stock
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
