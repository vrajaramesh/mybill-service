package com.example.mybill.boutique.service;

import com.example.mybill.boutique.exception.AgentApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Collections (db/boutique/V13): one Instagram post with several items of the same kind and price - fabrics sold by
 * the metre (FABRIC) or readymade garments with their own sizes (READYMADE). Each item is a Products row with the code
 * printed on its photo (e.g. MCS-1A). Price, stock, sizes and photos always come from Products.
 * Used by the mybill-ui Collections page and, read-only, by the WhatsApp assistant (/api/agent/collections).
 * JSON keys are snake_case for both.
 * DROPSHIP collections (V16) are a supplier's products: readymade-like (pieces, sizes) but the supplier holds the stock,
 * and they carry the supplier and its courier charges per zone. The supplier and cost prices are only in the staff view.
 * WHOLESALE collections (V17) are a wholesale product's designs: items point to wholesale_products (the Wholesale
 * module), which gives name, unit, photo, stock, price slabs, minimum order and GST (WholesaleCollectionService).
 */
@Service
public class CollectionService {

    public static final String FABRIC = "FABRIC", READYMADE = "READYMADE", DROPSHIP = "DROPSHIP", WHOLESALE = "WHOLESALE";
    static final Set<String> METRE_UNITS = Set.of("m", "meter", "meters", "metre", "metres", "mts", "mtr", "mtrs");
    static final Set<String> PIECE_UNITS = Set.of("pcs", "pc", "piece", "pieces", "nos", "no");
    static final int MAX_ITEMS = 60;
    private static final String CODE = "^[A-Z0-9][A-Z0-9-]{1,29}$";

    @PersistenceContext private EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private DropshipService dropship;
    @org.springframework.beans.factory.annotation.Autowired private WholesaleCollectionService wholesale;

    public static boolean isMetres(String unit) {
        return unit != null && METRE_UNITS.contains(unit.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isPieces(String unit) {
        return unit != null && PIECE_UNITS.contains(unit.trim().toLowerCase(Locale.ROOT));
    }

    // ── read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : em.createNativeQuery("""
                SELECT c.collection_id, c.code, c.name, c.collection_type, c.is_active, c.cover_image_url,
                       (SELECT COUNT(*) FROM boutique_collection_items i WHERE i.collection_id = c.collection_id)
                  FROM boutique_collections c ORDER BY c.created_at DESC, c.collection_id DESC""").getResultList()) {
            Object[] a = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("collection_id", a[0]);
            m.put("code", a[1]);
            m.put("name", a[2]);
            m.put("type", a[3]);
            m.put("active", a[4]);
            m.put("cover_image_url", a[5]);
            m.put("item_count", ((Number) a[6]).intValue());
            out.add(m);
        }
        return out;
    }

    /** The assistant's view: a collection with its items (product name, unit, price, stock, sizes, photo, instructions). */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String codeOrId) {
        return get(codeOrId, false);
    }

    /** staff = mybill-ui: also the supplier, courier charges and cost prices of a dropship collection. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String codeOrId, boolean staff) {
        Object[] c = header(codeOrId);
        int id = ((Number) c[0]).intValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collection_id", id);
        out.put("code", c[1]);
        out.put("name", c[2]);
        out.put("type", c[3]);
        out.put("description", c[4]);
        out.put("cover_image_url", c[5]);
        out.put("ai_instructions", c[6]);
        out.put("active", c[7]);
        boolean isWholesale = WHOLESALE.equals(c[3]);
        List<Map<String, Object>> items = isWholesale ? wholesaleItems(id) : items(id);
        boolean isDropship = DROPSHIP.equals(c[3]);
        Optional<DropshipService.Supplier> supplier = isDropship ? dropship.supplier(c[8] == null ? null : ((Number) c[8]).intValue())
            : Optional.empty();
        DropshipService.Slabs slabs = dropship.parseSlabs((String) c[9], c[10] == null ? null : new BigDecimal(c[10].toString()));
        if (isDropship) {
            out.put("dropship", true);
            out.put("dispatch_days", supplier.map(DropshipService.Supplier::dispatchDays).orElse(null));
        }
        Map<String, Object> pricing = isWholesale ? wholesalePricing(items) : null;
        if (isWholesale) out.put("wholesale", pricing);
        if (staff) {
            out.put("supplier_id", c[8]);
            out.put("supplier_name", supplier.map(DropshipService.Supplier::name).orElse(null));
            out.put("courier_slabs", slabs.perZone());
            out.put("courier_extra_per_piece", c[10]);
        } else {
            items.forEach(i -> i.remove("cost_price"));
            if (isWholesale) items.forEach(i -> i.remove("stock"));   // the assistant only says in stock or not
        }
        out.put("items", items);
        List<String> warnings = warnings((String) c[3], items, supplier, slabs, isDropship && c[8] != null);
        if (isWholesale) warnings.addAll(wholesaleWarnings(items, pricing));
        out.put("warnings", warnings);
        if (!staff) out.remove("warnings");
        return out;
    }

    /** One item by its code (e.g. MCS-1B), with its collection's code, name, type and instructions. */
    @Transactional(readOnly = true)
    public Map<String, Object> item(String itemCode) {
        List<?> rows = em.createNativeQuery("SELECT collection_id FROM boutique_collection_items WHERE UPPER(item_code) = UPPER(?1)")
            .setParameter(1, itemCode.trim()).getResultList();
        if (rows.isEmpty()) throw AgentApiException.notFound("ITEM_NOT_FOUND", "No collection item " + itemCode);
        Map<String, Object> collection = get(String.valueOf(((Number) rows.get(0)).intValue()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) collection.remove("items");
        Map<String, Object> item = items.stream()
            .filter(i -> itemCode.trim().equalsIgnoreCase((String) i.get("item_code"))).findFirst().orElseThrow();
        collection.remove("warnings");
        item.put("collection", collection);
        return item;
    }

    private Object[] header(String codeOrId) {
        boolean numeric = codeOrId.matches("\\d{1,9}");
        List<?> rows = em.createNativeQuery("""
                SELECT collection_id, code, name, collection_type, description, cover_image_url, ai_instructions, is_active,
                       supplier_id, courier_slabs, courier_extra_per_piece
                  FROM boutique_collections""" + (numeric ? " WHERE collection_id = CAST(?1 AS INTEGER)" : " WHERE UPPER(code) = UPPER(?1)"))
            .setParameter(1, codeOrId.trim()).getResultList();
        if (rows.isEmpty()) throw AgentApiException.notFound("COLLECTION_NOT_FOUND", "No collection " + codeOrId);
        return (Object[]) rows.get(0);
    }

    private List<Map<String, Object>> items(int collectionId) {
        List<?> rows = em.createNativeQuery("""
                SELECT i.item_id, i.item_code, i.product_id, i.colour, i.image_url, i.sort_order,
                       p.product_name, p.unit, p.selling_price, p.stock_quantity, p.is_active, p.is_online, p.ai_instructions,
                       p.description, p.cost_price,
                       (SELECT pi.image_url FROM product_images pi WHERE pi.product_id = i.product_id
                          AND COALESCE(pi.media_type, 'image') = 'image' ORDER BY COALESCE(pi.sort_order, 0), pi.image_id LIMIT 1)
                  FROM boutique_collection_items i LEFT JOIN products p ON p.product_id = i.product_id
                 WHERE i.collection_id = ?1 ORDER BY i.sort_order, i.item_id""")
            .setParameter(1, collectionId).getResultList();
        Map<Integer, List<Map<String, Object>>> sizes = sizesFor(rows.stream().map(r -> ((Number) ((Object[]) r)[2]).intValue()).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] a = (Object[]) r;
            int productId = ((Number) a[2]).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_id", a[0]);
            m.put("item_code", a[1]);
            m.put("product_id", productId);
            m.put("colour", a[3]);
            m.put("image_url", a[4] != null ? a[4] : a[15]);
            m.put("product_name", a[6]);
            m.put("unit", a[7]);
            m.put("price", a[8] == null ? null : PricingService.money(new BigDecimal(a[8].toString())));
            m.put("stock", a[9] == null ? BigDecimal.ZERO : new BigDecimal(a[9].toString()));
            m.put("available", a[6] != null && Boolean.TRUE.equals(a[10]) && !Boolean.FALSE.equals(a[11]));
            m.put("ai_instructions", a[12]);
            m.put("description", a[13]);
            m.put("cost_price", a[14] == null ? null : PricingService.money(new BigDecimal(a[14].toString())));
            m.put("sizes", sizes.getOrDefault(productId, List.of()));
            out.add(m);
        }
        return out;
    }

    /** Items of a WHOLESALE collection, filled from their wholesale products. */
    private List<Map<String, Object>> wholesaleItems(int collectionId) {
        List<?> rows = em.createNativeQuery("""
                SELECT item_id, item_code, wholesale_product_id, colour, image_url FROM boutique_collection_items
                 WHERE collection_id = ?1 ORDER BY sort_order, item_id""").setParameter(1, collectionId).getResultList();
        Set<Integer> ids = new HashSet<>();
        for (Object r : rows) if (((Object[]) r)[2] != null) ids.add(((Number) ((Object[]) r)[2]).intValue());
        Map<Integer, WholesaleCollectionService.Product> products = wholesale.products(ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] a = (Object[]) r;
            WholesaleCollectionService.Product p = a[2] == null ? null : products.get(((Number) a[2]).intValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_id", a[0]);
            m.put("item_code", a[1]);
            m.put("wholesale_product_id", a[2]);
            m.put("colour", a[3]);
            m.put("image_url", a[4] != null ? a[4] : p == null ? null : p.imageUrl());
            m.put("product_name", p == null ? null : p.name());
            m.put("product_code", p == null ? null : p.code());
            m.put("unit", p == null ? null : p.unit());
            m.put("stock", p == null ? BigDecimal.ZERO : WholesaleCollectionService.plain(p.available()));
            m.put("in_stock", p != null && p.available().signum() > 0);
            m.put("available", p != null && p.active());
            m.put("ai_instructions", p == null ? null : p.aiInstructions());
            m.put("description", p == null ? null : p.description());
            out.add(m);
        }
        return out;
    }

    /**
     * Price slabs and minimum order of the collection's wholesale product (or, if items use several products, of each).
     * They apply to the collection's TOTAL quantity across its codes.
     */
    private Map<String, Object> wholesalePricing(List<Map<String, Object>> items) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Map<String, Object> i : items) if (i.get("wholesale_product_id") instanceof Number n) ids.add(n.intValue());
        Map<Integer, WholesaleCollectionService.Product> products = wholesale.products(ids);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> each = new ArrayList<>();
        for (Integer id : ids) {
            WholesaleCollectionService.Product p = products.get(id);
            if (p == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_name", p.name());
            m.putAll(wholesale.pricing(p));
            each.add(m);
        }
        if (each.size() == 1) out.putAll(each.get(0));
        else out.put("products", each);
        out.put("note", "Prices are per unit and exclude GST. The price slab and the minimum order apply to the TOTAL "
            + "quantity of all codes of this collection together. Transport is extra - our team adds it in the final quotation.");
        return out;
    }

    private static List<String> wholesaleWarnings(List<Map<String, Object>> items, Map<String, Object> pricing) {
        List<String> out = new ArrayList<>();
        Set<String> units = new TreeSet<>();
        for (Map<String, Object> i : items) {
            String code = (String) i.get("item_code");
            if (i.get("product_name") == null) { out.add(code + ": choose the wholesale product"); continue; }
            if (!Boolean.TRUE.equals(i.get("available"))) out.add(code + ": wholesale product is inactive");
            else if (!Boolean.TRUE.equals(i.get("in_stock"))) out.add(code + ": wholesale product is out of stock");
            if (i.get("unit") != null) units.add(String.valueOf(i.get("unit")));
        }
        List<Map<String, Object>> products = pricing.get("products") instanceof List<?> l
            ? l.stream().map(o -> (Map<String, Object>) castMap(o)).toList() : List.of(pricing);
        for (Map<String, Object> p : products) {
            if (p.get("product_name") != null && (!(p.get("price_slabs") instanceof List<?> slabs) || slabs.isEmpty())) {
                out.add(p.get("product_name") + ": no price slabs in Wholesale → Pricing - buyers can't order");
            }
        }
        if (units.size() > 1) out.add("Items are sold in different units " + units + " - one collection should use one unit");
        if (products.size() > 1) out.add("Items use different wholesale products - each product's own slabs and minimum apply");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private Map<Integer, List<Map<String, Object>>> sizesFor(List<Integer> productIds) {
        Map<Integer, List<Map<String, Object>>> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        for (Object r : em.createNativeQuery("""
                SELECT product_id, size_label, stock FROM product_size_stock
                 WHERE product_id IN (?1) ORDER BY product_id, sort_order, size_label""")
                .setParameter(1, productIds).getResultList()) {
            Object[] a = (Object[]) r;
            out.computeIfAbsent(((Number) a[0]).intValue(), k -> new ArrayList<>())
                .add(Map.of("size", a[1], "stock", new BigDecimal(a[2].toString())));
        }
        return out;
    }

    /** Things staff should fix: wrong unit for the type, missing sizes, out of stock, prices that differ; dropship setup. */
    private static List<String> warnings(String type, List<Map<String, Object>> items, Optional<DropshipService.Supplier> supplier,
                                         DropshipService.Slabs slabs, boolean hasSupplierId) {
        List<String> out = new ArrayList<>();
        if (WHOLESALE.equals(type)) return out;   // see wholesaleWarnings
        if (DROPSHIP.equals(type)) {
            if (supplier.isEmpty()) out.add(hasSupplierId ? "The supplier no longer exists - choose one" : "Choose the supplier");
            else if (!supplier.get().active()) out.add("Supplier " + supplier.get().name() + " is inactive - customers can't order");
            else if (supplier.get().pincode() == null) out.add("Supplier " + supplier.get().name() + " has no pincode - every order is charged the NATIONAL slab");
            if (slabs.perZone().isEmpty()) out.add("Enter the courier charges (at least NATIONAL) - customers can't order without them");
            else if (!slabs.perZone().containsKey("NATIONAL") && !slabs.perZone().containsKey("SPECIAL")) {
                out.add("No NATIONAL courier charge - far-away pincodes use the nearest zone's charge");
            }
        }
        Set<BigDecimal> prices = new TreeSet<>();
        for (Map<String, Object> i : items) {
            String code = (String) i.get("item_code");
            if (i.get("product_name") == null) { out.add(code + ": product no longer exists"); continue; }
            String unit = (String) i.get("unit");
            if (FABRIC.equals(type) && !isMetres(unit)) out.add(code + ": unit is '" + unit + "', fabric items must be sold in metres");
            if (READYMADE.equals(type) && !isPieces(unit)) out.add(code + ": unit is '" + unit + "', readymade items must be in pieces");
            if (READYMADE.equals(type) && ((List<?>) i.get("sizes")).isEmpty()) out.add(code + ": no sizes with stock entered in Products");
            if (DROPSHIP.equals(type) && !isPieces(unit)) out.add(code + ": unit is '" + unit + "', dropship items must be in pieces");
            if (DROPSHIP.equals(type) && i.get("cost_price") == null) out.add(code + ": no cost price (what the supplier charges) in Products");
            if (!DROPSHIP.equals(type) && ((BigDecimal) i.get("stock")).signum() <= 0) out.add(code + ": out of stock");
            if (!Boolean.TRUE.equals(i.get("available"))) out.add(code + ": product is inactive or not online");
            if (i.get("price") != null) prices.add((BigDecimal) i.get("price"));
        }
        if (prices.size() > 1) out.add("Items have different prices: " + prices + " – a collection usually has one price");
        return out;
    }

    // ── write (mybill-ui) ─────────────────────────────────────────────────────

    /** Create (collectionId null) or update. Body: code, name, type, description, cover_image_url, ai_instructions, active. */
    @Transactional
    public Map<String, Object> save(Integer collectionId, Map<String, Object> body) {
        String code = str(body.get("code")).toUpperCase(Locale.ROOT);
        String name = str(body.get("name"));
        String type = str(body.get("type")).toUpperCase(Locale.ROOT);
        if (!code.matches(CODE)) throw bad("Code must be 2-30 letters, digits or '-', e.g. MCS-1");
        if (name.isEmpty()) throw bad("Name is required");
        if (!FABRIC.equals(type) && !READYMADE.equals(type) && !DROPSHIP.equals(type) && !WHOLESALE.equals(type)) {
            throw bad("Type must be FABRIC, READYMADE, DROPSHIP or WHOLESALE");
        }
        boolean isDropship = DROPSHIP.equals(type);
        Integer supplierId = null;
        if (isDropship) {
            if (body.get("supplier_id") instanceof Number n) supplierId = n.intValue();
            else if (!str(body.get("supplier_id")).isEmpty()) supplierId = Integer.valueOf(str(body.get("supplier_id")));
            if (supplierId == null) throw bad("Choose the supplier");
            if (dropship.supplier(supplierId).isEmpty()) throw bad("No supplier " + supplierId);
        }
        String slabs = isDropship ? dropship.slabsJson(body.get("courier_slabs")) : null;
        BigDecimal extra = null;
        if (isDropship && !str(body.get("courier_extra_per_piece")).isEmpty()) {
            try {
                extra = new BigDecimal(str(body.get("courier_extra_per_piece")));
            } catch (NumberFormatException e) {
                throw bad("Extra courier charge per piece must be a number");
            }
            if (extra.signum() < 0) throw bad("Extra courier charge per piece can't be negative");
        }
        List<?> clash = em.createNativeQuery("SELECT collection_id FROM boutique_collections WHERE UPPER(code) = ?1")
            .setParameter(1, code).getResultList();
        if (!clash.isEmpty() && (collectionId == null || ((Number) clash.get(0)).intValue() != collectionId)) {
            throw new AgentApiException(HttpStatus.CONFLICT, "COLLECTION_CODE_TAKEN", "Code " + code + " is already used", null);
        }
        boolean active = !(body.get("active") instanceof Boolean b) || b;
        NativeQuery<?> q;
        if (collectionId == null) {
            q = em.createNativeQuery("""
                    INSERT INTO boutique_collections (code, name, collection_type, description, cover_image_url, ai_instructions, is_active,
                                                      supplier_id, courier_slabs, courier_extra_per_piece)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10) RETURNING collection_id""").unwrap(NativeQuery.class);
        } else {
            header(String.valueOf(collectionId));
            q = em.createNativeQuery("""
                    UPDATE boutique_collections SET code = ?1, name = ?2, collection_type = ?3, description = ?4,
                           cover_image_url = ?5, ai_instructions = ?6, is_active = ?7, supplier_id = ?8, courier_slabs = ?9,
                           courier_extra_per_piece = ?10, updated_at = NOW()
                     WHERE collection_id = ?11 RETURNING collection_id""").unwrap(NativeQuery.class);
            q.setParameter(11, collectionId, Integer.class);
        }
        q.setParameter(1, code, String.class).setParameter(2, name, String.class).setParameter(3, type, String.class)
            .setParameter(4, nullable(body.get("description")), String.class)
            .setParameter(5, nullable(body.get("cover_image_url")), String.class)
            .setParameter(6, nullable(body.get("ai_instructions")), String.class)
            .setParameter(7, active, Boolean.class)
            .setParameter(8, supplierId, Integer.class)
            .setParameter(9, slabs, String.class)
            .setParameter(10, extra, BigDecimal.class);
        int id = ((Number) q.getSingleResult()).intValue();
        return get(String.valueOf(id), true);
    }

    /** Replace the items: [{item_code, product_id (wholesale: wholesale_product_id), colour?, image_url?}] in display order. */
    @Transactional
    public Map<String, Object> replaceItems(int collectionId, List<Map<String, Object>> items) {
        boolean isWholesale = WHOLESALE.equals(header(String.valueOf(collectionId))[3]);
        String idKey = isWholesale ? "wholesale_product_id" : "product_id";
        if (items.size() > MAX_ITEMS) throw bad("At most " + MAX_ITEMS + " items per collection");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> item : items) {
            String code = str(item.get("item_code")).toUpperCase(Locale.ROOT);
            if (!code.matches(CODE)) throw bad("Item code '" + code + "' must be 2-30 letters, digits or '-', e.g. MCS-1A");
            if (!codes.add(code)) throw bad("Item code " + code + " is used twice");
            if (!(item.get(idKey) instanceof Number)) throw bad("Choose a " + (isWholesale ? "wholesale " : "") + "product for " + code);
        }
        if (!codes.isEmpty()) {
            List<?> elsewhere = em.createNativeQuery("""
                    SELECT i.item_code, c.code FROM boutique_collection_items i JOIN boutique_collections c ON c.collection_id = i.collection_id
                     WHERE UPPER(i.item_code) IN (?1) AND i.collection_id <> ?2""")
                .setParameter(1, codes).setParameter(2, collectionId).getResultList();
            if (!elsewhere.isEmpty()) {
                Object[] a = (Object[]) elsewhere.get(0);
                throw new AgentApiException(HttpStatus.CONFLICT, "ITEM_CODE_TAKEN", "Item code " + a[0] + " is already in collection " + a[1], null);
            }
        }
        if (isWholesale) {
            Set<Integer> ids = new HashSet<>();
            items.forEach(i -> ids.add(((Number) i.get(idKey)).intValue()));
            Set<Integer> known = wholesale.products(ids).keySet();
            for (Integer id : ids) if (!known.contains(id)) throw bad("No wholesale product " + id);
        }
        em.createNativeQuery("DELETE FROM boutique_collection_items WHERE collection_id = ?1").setParameter(1, collectionId).executeUpdate();
        int order = 0;
        for (Map<String, Object> item : items) {
            em.createNativeQuery("""
                    INSERT INTO boutique_collection_items (collection_id, item_code, product_id, colour, image_url, sort_order,
                                                           wholesale_product_id)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)""").unwrap(NativeQuery.class)
                .setParameter(1, collectionId, Integer.class)
                .setParameter(2, str(item.get("item_code")).toUpperCase(Locale.ROOT), String.class)
                .setParameter(3, isWholesale ? null : ((Number) item.get("product_id")).intValue(), Integer.class)
                .setParameter(7, isWholesale ? ((Number) item.get(idKey)).intValue() : null, Integer.class)
                .setParameter(4, nullable(item.get("colour")), String.class)
                .setParameter(5, nullable(item.get("image_url")), String.class)
                .setParameter(6, order++, Integer.class)
                .executeUpdate();
        }
        return get(String.valueOf(collectionId), true);
    }

    @Transactional
    public void delete(int collectionId) {
        Object links = em.createNativeQuery("""
                SELECT COUNT(*) FROM marketing_links l JOIN boutique_collections c ON c.code = l.collection_code
                 WHERE c.collection_id = ?1""").setParameter(1, collectionId).getSingleResult();
        if (((Number) links).intValue() > 0) {
            throw new AgentApiException(HttpStatus.CONFLICT, "COLLECTION_IN_USE",
                "This collection is used by an Ad & post link - remove the link first, or mark the collection inactive", null);
        }
        em.createNativeQuery("DELETE FROM boutique_collections WHERE collection_id = ?1").setParameter(1, collectionId).executeUpdate();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String nullable(Object o) {
        String s = str(o);
        return s.isEmpty() ? null : s;
    }

    private static AgentApiException bad(String message) {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, null);
    }
}
