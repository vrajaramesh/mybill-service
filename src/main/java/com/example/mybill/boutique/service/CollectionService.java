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
    /** Collection lifecycle (V18): DRAFT until approved, ACTIVE = the assistant sells it (is_active), ARCHIVED. */
    public static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "ARCHIVED");
    public static final String AVAILABLE = "AVAILABLE", SOLD_OUT = "SOLD_OUT";
    /** Variant details (V18) kept from the stored row when the UI saves items without them. */
    private static final List<String> VARIANT_FIELDS = List.of("variant_no", "secondary_desc", "original_image_path",
        "stamped_image_url", "image_phash", "status");
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
                       (SELECT COUNT(*) FROM boutique_collection_items i WHERE i.collection_id = c.collection_id), c.status,
                       (SELECT s.name FROM boutique_suppliers s WHERE s.supplier_id = c.supplier_id),
                       (SELECT MIN(COALESCE(d.selling_price, p.selling_price)) FROM boutique_collection_items i
                          LEFT JOIN products p ON p.product_id = i.product_id
                          LEFT JOIN dropship_products d ON d.dropship_product_id = i.dropship_product_id
                         WHERE i.collection_id = c.collection_id),
                       (SELECT COUNT(*) FROM boutique_collection_items i WHERE i.collection_id = c.collection_id
                           AND COALESCE(i.status, 'AVAILABLE') = 'AVAILABLE'),
                       (SELECT l.permalink FROM marketing_links l WHERE l.collection_code = c.code AND l.permalink IS NOT NULL
                         ORDER BY l.active DESC, l.created_at DESC LIMIT 1),
                       c.source, c.owner_phone
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
            m.put("status", a[7]);
            m.put("supplier_name", a[8]);
            m.put("price", a[9]);
            m.put("available_count", a[10] == null ? 0 : ((Number) a[10]).intValue());
            m.put("instagram_url", a[11]);
            m.put("source", a[12]);
            m.put("owner_phone", a[13]);
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
        out.put("status", c[11]);
        out.put("source", c[12]);
        out.put("shipping_extra", c[13]);
        boolean isWholesale = WHOLESALE.equals(c[3]);
        boolean isDropship = DROPSHIP.equals(c[3]);
        List<Map<String, Object>> items = isWholesale ? wholesaleItems(id) : isDropship ? dropshipItems(id) : items(id);
        Optional<DropshipService.Supplier> supplier = isDropship ? dropship.supplier(c[8] == null ? null : ((Number) c[8]).intValue())
            : Optional.empty();
        DropshipService.Slabs slabs = dropship.parseSlabs((String) c[9], c[10] == null ? null : new BigDecimal(c[10].toString()));
        if (isDropship) {
            out.put("dropship", true);
            // a post forwarded on WhatsApp has a collage of all its colours (phase 4)
            List<?> collage = em.createNativeQuery("""
                    SELECT CAST(extracted AS jsonb) ->> 'collage_url' FROM wa_intake_batch
                     WHERE collection_id = ?1 AND extracted IS NOT NULL ORDER BY batch_id DESC LIMIT 1""")
                .setParameter(1, id).getResultList();
            if (!collage.isEmpty() && collage.get(0) != null) out.put("collage_url", collage.get(0));
            out.put("dispatch_days", supplier.map(DropshipService.Supplier::dispatchDays).orElse(null));
        }
        Map<String, Object> pricing = isWholesale ? wholesalePricing(items) : null;
        if (isWholesale) out.put("wholesale", pricing);
        if (staff) {
            List<?> ig = em.createNativeQuery("""
                    SELECT permalink FROM marketing_links WHERE collection_code = ?1 AND permalink IS NOT NULL
                     ORDER BY active DESC, created_at DESC""").setParameter(1, c[1]).getResultList();
            out.put("instagram_urls", ig);
            out.put("supplier_id", c[8]);
            out.put("owner_phone", c[14]);
            out.put("supplier_name", supplier.map(DropshipService.Supplier::name).orElse(null));
            out.put("courier_slabs", slabs.perZone());
            out.put("courier_extra_per_piece", c[10]);
        } else {
            items.removeIf(i -> SOLD_OUT.equals(i.get("status")));
            items.forEach(i -> { i.remove("cost_price"); i.remove("original_image_path"); i.remove("image_phash"); });
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
                       supplier_id, courier_slabs, courier_extra_per_piece, status, source, shipping_extra, owner_phone
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
                          AND COALESCE(pi.media_type, 'image') = 'image' ORDER BY COALESCE(pi.sort_order, 0), pi.image_id LIMIT 1),
                       i.variant_no, i.secondary_desc, i.original_image_path, i.stamped_image_url, i.image_phash, i.status
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
            putVariant(m, a, 16);
            out.add(m);
        }
        return out;
    }

    /** Items of a DROPSHIP collection: their variants live in dropship_products (V21), not in Products. */
    private List<Map<String, Object>> dropshipItems(int collectionId) {
        List<?> rows = em.createNativeQuery("""
                SELECT i.item_id, i.item_code, i.dropship_product_id, i.colour, i.image_url, d.image_url,
                       d.product_name, d.selling_price, d.is_active, d.ai_instructions, d.description, d.cost_price, d.sizes,
                       i.variant_no, i.secondary_desc, i.original_image_path, i.stamped_image_url, i.image_phash, i.status
                  FROM boutique_collection_items i LEFT JOIN dropship_products d ON d.dropship_product_id = i.dropship_product_id
                 WHERE i.collection_id = ?1 ORDER BY i.sort_order, i.item_id""")
            .setParameter(1, collectionId).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] a = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_id", a[0]);
            m.put("item_code", a[1]);
            m.put("product_id", null);
            m.put("dropship_product_id", a[2]);
            m.put("colour", a[3]);
            m.put("image_url", a[4] != null ? a[4] : a[5]);
            m.put("product_name", a[6]);
            m.put("unit", "pcs");
            m.put("price", a[7] == null ? null : PricingService.money(new BigDecimal(a[7].toString())));
            m.put("stock", BigDecimal.ZERO);   // the supplier holds the stock
            m.put("available", a[6] != null && Boolean.TRUE.equals(a[8]));
            m.put("ai_instructions", a[9]);
            m.put("description", a[10]);
            m.put("cost_price", a[11] == null ? null : PricingService.money(new BigDecimal(a[11].toString())));
            List<Map<String, Object>> sizes = new ArrayList<>();
            for (String size : sizeList((String) a[12])) sizes.add(Map.of("size", size, "stock", BigDecimal.ZERO));
            m.put("sizes", sizes);
            putVariant(m, a, 13);
            out.add(m);
        }
        return out;
    }

    /** "S, M,L" -> [S, M, L]. */
    public static List<String> sizeList(String sizes) {
        if (sizes == null || sizes.isBlank()) return List.of();
        return Arrays.stream(sizes.split(",")).map(String::trim).filter(x -> !x.isEmpty()).distinct().toList();
    }

    /** A new dropship variant (one colour). Returns its dropship_product_id. */
    public int insertDropshipProduct(int collectionId, String name, String description, String category, BigDecimal cost,
                                     BigDecimal price, String sizes, String tags, String imageUrl) {
        Object id = em.createNativeQuery("""
                INSERT INTO dropship_products (collection_id, product_name, description, category, cost_price, selling_price,
                                               sizes, tags, image_url)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9) RETURNING dropship_product_id""").unwrap(NativeQuery.class)
            .setParameter(1, collectionId, Integer.class)
            .setParameter(2, clip(name, 200), String.class)
            .setParameter(3, description == null || description.isBlank() ? null : description.trim(), String.class)
            .setParameter(4, category == null ? null : clip(category, 50), String.class)
            .setParameter(5, cost, BigDecimal.class)
            .setParameter(6, price, BigDecimal.class)
            .setParameter(7, sizes == null || sizes.isBlank() ? null : clip(String.join(",", sizeList(sizes)), 200), String.class)
            .setParameter(8, tags == null || tags.isBlank() ? null : tags, String.class)
            .setParameter(9, imageUrl, String.class)
            .getSingleResult();
        return ((Number) id).intValue();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** The V18 variant columns, starting at row index `from`. */
    private static void putVariant(Map<String, Object> m, Object[] a, int from) {
        m.put("variant_no", a[from]);
        m.put("secondary_desc", a[from + 1]);
        m.put("original_image_path", a[from + 2]);
        m.put("stamped_image_url", a[from + 3]);
        m.put("image_phash", a[from + 4]);
        m.put("status", a[from + 5] == null ? AVAILABLE : a[from + 5]);
    }

    /** Items of a WHOLESALE collection, filled from their wholesale products. */
    private List<Map<String, Object>> wholesaleItems(int collectionId) {
        List<?> rows = em.createNativeQuery("""
                SELECT item_id, item_code, wholesale_product_id, colour, image_url,
                       variant_no, secondary_desc, original_image_path, stamped_image_url, image_phash, status
                  FROM boutique_collection_items
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
            putVariant(m, a, 5);
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
            if (i.get("product_name") == null) {
                out.add(code + (DROPSHIP.equals(type) ? ": enter its name and price" : ": product no longer exists"));
                continue;
            }
            String unit = (String) i.get("unit");
            if (FABRIC.equals(type) && !isMetres(unit)) out.add(code + ": unit is '" + unit + "', fabric items must be sold in metres");
            if (READYMADE.equals(type) && !isPieces(unit)) out.add(code + ": unit is '" + unit + "', readymade items must be in pieces");
            if (READYMADE.equals(type) && ((List<?>) i.get("sizes")).isEmpty()) out.add(code + ": no sizes with stock entered in Products");
            if (DROPSHIP.equals(type) && i.get("cost_price") == null) out.add(code + ": no cost price (what the supplier charges)");
            if (!DROPSHIP.equals(type) && ((BigDecimal) i.get("stock")).signum() <= 0) out.add(code + ": out of stock");
            if (!Boolean.TRUE.equals(i.get("available"))) out.add(code + (DROPSHIP.equals(type) ? ": inactive" : ": product is inactive or not online"));
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
        String existingStatus = collectionId == null ? null : (String) header(String.valueOf(collectionId))[11];
        String status = str(body.get("status")).toUpperCase(Locale.ROOT);
        if (status.isEmpty()) {
            boolean wantsActive = !(body.get("active") instanceof Boolean b) || b;
            status = wantsActive ? "ACTIVE" : "DRAFT".equals(existingStatus) ? "DRAFT" : "ARCHIVED";
        }
        if (!STATUSES.contains(status)) throw bad("Status must be DRAFT, ACTIVE or ARCHIVED");
        boolean active = "ACTIVE".equals(status);
        String source = str(body.get("source")).toUpperCase(Locale.ROOT);
        if (source.isEmpty()) source = "MANUAL";
        if (!source.equals("MANUAL") && !source.equals("WHATSAPP_FORWARD")) throw bad("Source must be MANUAL or WHATSAPP_FORWARD");
        boolean shippingExtra = !(body.get("shipping_extra") instanceof Boolean se) || se;
        NativeQuery<?> q;
        if (collectionId == null) {
            q = em.createNativeQuery("""
                    INSERT INTO boutique_collections (code, name, collection_type, description, cover_image_url, ai_instructions, is_active,
                                                      supplier_id, courier_slabs, courier_extra_per_piece, status, source, shipping_extra)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13) RETURNING collection_id""").unwrap(NativeQuery.class);
        } else {
            header(String.valueOf(collectionId));
            q = em.createNativeQuery("""
                    UPDATE boutique_collections SET code = ?1, name = ?2, collection_type = ?3, description = ?4,
                           cover_image_url = ?5, ai_instructions = ?6, is_active = ?7, supplier_id = ?8, courier_slabs = ?9,
                           courier_extra_per_piece = ?10, status = ?11, source = COALESCE(source, ?12), shipping_extra = ?13,
                           updated_at = NOW()
                     WHERE collection_id = ?14 RETURNING collection_id""").unwrap(NativeQuery.class);
            q.setParameter(14, collectionId, Integer.class);
        }
        q.setParameter(1, code, String.class).setParameter(2, name, String.class).setParameter(3, type, String.class)
            .setParameter(4, nullable(body.get("description")), String.class)
            .setParameter(5, nullable(body.get("cover_image_url")), String.class)
            .setParameter(6, nullable(body.get("ai_instructions")), String.class)
            .setParameter(7, active, Boolean.class)
            .setParameter(8, supplierId, Integer.class)
            .setParameter(9, slabs, String.class)
            .setParameter(10, extra, BigDecimal.class)
            .setParameter(11, status, String.class)
            .setParameter(12, source, String.class)
            .setParameter(13, shippingExtra, Boolean.class);
        int id = ((Number) q.getSingleResult()).intValue();
        return get(String.valueOf(id), true);
    }

    /**
     * Replace the items: [{item_code, product_id (wholesale: wholesale_product_id), colour?, image_url?}] in display order.
     * Dropship: {item_code, dropship_product_id?, product_name, price, cost_price?, sizes?, ...} - a variant without
     * dropship_product_id is created in dropship_products; name / prices / sizes sent for an existing one update it.
     */
    @Transactional
    public Map<String, Object> replaceItems(int collectionId, List<Map<String, Object>> items) {
        Object[] head = header(String.valueOf(collectionId));
        boolean isWholesale = WHOLESALE.equals(head[3]);
        boolean isDropship = DROPSHIP.equals(head[3]);
        String idKey = isWholesale ? "wholesale_product_id" : "product_id";
        if (items.size() > MAX_ITEMS) throw bad("At most " + MAX_ITEMS + " items per collection");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> item : items) {
            String code = str(item.get("item_code")).toUpperCase(Locale.ROOT);
            if (!code.matches(CODE)) throw bad("Item code '" + code + "' must be 2-30 letters, digits or '-', e.g. MCS-1A");
            if (!codes.add(code)) throw bad("Item code " + code + " is used twice");
            if (isDropship) {
                if (!(item.get("dropship_product_id") instanceof Number) && (str(item.get("product_name")).isEmpty() || str(item.get("price")).isEmpty())) {
                    throw bad("Enter the name and selling price of " + code);
                }
            } else if (!(item.get(idKey) instanceof Number)) {
                throw bad("Choose a " + (isWholesale ? "wholesale " : "") + "product for " + code);
            }
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
        Map<String, Object[]> kept = new HashMap<>();   // ITEM CODE -> stored V18 variant details
        for (Object r : em.createNativeQuery("""
                SELECT UPPER(item_code), variant_no, secondary_desc, original_image_path, stamped_image_url, image_phash, status
                  FROM boutique_collection_items WHERE collection_id = ?1""").setParameter(1, collectionId).getResultList()) {
            Object[] a = (Object[]) r;
            kept.put((String) a[0], Arrays.copyOfRange(a, 1, 7));
        }
        Map<String, Integer> dropshipIds = isDropship ? saveDropshipProducts(collectionId, items) : Map.of();
        em.createNativeQuery("DELETE FROM boutique_collection_items WHERE collection_id = ?1").setParameter(1, collectionId).executeUpdate();
        int order = 0;
        for (Map<String, Object> item : items) {
            em.createNativeQuery("""
                    INSERT INTO boutique_collection_items (collection_id, item_code, product_id, colour, image_url, sort_order,
                                                           wholesale_product_id, variant_no, secondary_desc, original_image_path,
                                                           stamped_image_url, image_phash, status, dropship_product_id)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)""").unwrap(NativeQuery.class)
                .setParameter(1, collectionId, Integer.class)
                .setParameter(2, str(item.get("item_code")).toUpperCase(Locale.ROOT), String.class)
                .setParameter(3, isWholesale || isDropship ? null : ((Number) item.get("product_id")).intValue(), Integer.class)
                .setParameter(14, dropshipIds.get(str(item.get("item_code")).toUpperCase(Locale.ROOT)), Integer.class)
                .setParameter(7, isWholesale ? ((Number) item.get(idKey)).intValue() : null, Integer.class)
                .setParameter(8, variantNo(variant(item, kept, 0)), Integer.class)
                .setParameter(9, (String) variant(item, kept, 1), String.class)
                .setParameter(10, (String) variant(item, kept, 2), String.class)
                .setParameter(11, (String) variant(item, kept, 3), String.class)
                .setParameter(12, (String) variant(item, kept, 4), String.class)
                .setParameter(13, itemStatus(variant(item, kept, 5)), String.class)
                .setParameter(4, nullable(item.get("colour")), String.class)
                .setParameter(5, nullable(item.get("image_url")), String.class)
                .setParameter(6, order++, Integer.class)
                .executeUpdate();
        }
        if (isDropship) {   // variants removed from the collection
            em.createNativeQuery("""
                    DELETE FROM dropship_products d WHERE d.collection_id = ?1
                       AND NOT EXISTS (SELECT 1 FROM boutique_collection_items i WHERE i.dropship_product_id = d.dropship_product_id)""")
                .setParameter(1, collectionId).executeUpdate();
        }
        return get(String.valueOf(collectionId), true);
    }

    /** Creates / updates the dropship_products rows of the items. ITEM CODE -> dropship_product_id. */
    private Map<String, Integer> saveDropshipProducts(int collectionId, List<Map<String, Object>> items) {
        Map<String, Integer> out = new HashMap<>();
        for (Map<String, Object> item : items) {
            String code = str(item.get("item_code")).toUpperCase(Locale.ROOT);
            String name = str(item.get("product_name"));
            BigDecimal price = str(item.get("price")).isEmpty() ? null : money(item.get("price"), code + " price");
            if (price != null && price.signum() <= 0) throw bad(code + ": the selling price must be more than 0");
            boolean hasCost = item.containsKey("cost_price");
            BigDecimal cost = str(item.get("cost_price")).isEmpty() ? null : money(item.get("cost_price"), code + " cost price");
            if (cost != null && cost.signum() < 0) throw bad(code + ": the cost price can't be negative");
            if (item.get("dropship_product_id") instanceof Number n) {
                int id = n.intValue();
                List<?> owner = em.createNativeQuery("SELECT collection_id FROM dropship_products WHERE dropship_product_id = ?1")
                    .setParameter(1, id).getResultList();
                if (owner.isEmpty() || owner.get(0) == null || ((Number) owner.get(0)).intValue() != collectionId) {
                    throw bad(code + ": that variant belongs to another collection");
                }
                em.createNativeQuery("""
                        UPDATE dropship_products SET product_name = COALESCE(?1, product_name), selling_price = COALESCE(?2, selling_price),
                               cost_price = CASE WHEN ?3 THEN ?4 ELSE cost_price END,
                               sizes = CASE WHEN ?5 THEN ?6 ELSE sizes END, updated_at = NOW()
                         WHERE dropship_product_id = ?7""").unwrap(NativeQuery.class)
                    .setParameter(1, name.isEmpty() ? null : clip(name, 200), String.class)
                    .setParameter(2, price, BigDecimal.class)
                    .setParameter(3, hasCost, Boolean.class)
                    .setParameter(4, cost, BigDecimal.class)
                    .setParameter(5, item.containsKey("sizes"), Boolean.class)
                    .setParameter(6, sizesText(item.get("sizes")), String.class)
                    .setParameter(7, id, Integer.class)
                    .executeUpdate();
                out.put(code, id);
            } else {
                out.put(code, insertDropshipProduct(collectionId, name, null, null, cost, price, sizesText(item.get("sizes")), null,
                    nullable(item.get("image_url"))));
            }
        }
        return out;
    }

    /** sizes as "S,M,L" from a list (["S","M"] or [{size:"S"}]) or a text; null = free size. */
    private static String sizesText(Object raw) {
        List<String> labels = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) labels.add(o instanceof Map<?, ?> m ? str(m.get("size")) : str(o));
        } else {
            labels.addAll(sizeList(str(raw)));
        }
        String joined = String.join(",", labels.stream().filter(x -> !x.isEmpty()).distinct().toList());
        return joined.isEmpty() ? null : clip(joined, 200);
    }

    private static BigDecimal money(Object raw, String label) {
        try {
            return new BigDecimal(str(raw).replace(",", "").replace("₹", "")).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw bad(label + " must be a number");
        }
    }

    /** A V18 variant field: from the request if it has the key, else the stored value for that item code. */
    private static Object variant(Map<String, Object> item, Map<String, Object[]> kept, int index) {
        String key = VARIANT_FIELDS.get(index);
        if (item.containsKey(key)) {
            Object v = item.get(key);
            return v == null || v.toString().isBlank() ? null : (v instanceof Number ? v : v.toString().trim());
        }
        Object[] stored = kept.get(str(item.get("item_code")).toUpperCase(Locale.ROOT));
        return stored == null ? null : stored[index];
    }

    private static Integer variantNo(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.valueOf(v.toString()); } catch (NumberFormatException e) { throw bad("Variant number must be a whole number"); }
    }

    private static String itemStatus(Object v) {
        String s = v == null ? AVAILABLE : v.toString().toUpperCase(Locale.ROOT);
        if (!AVAILABLE.equals(s) && !SOLD_OUT.equals(s)) throw bad("Item status must be AVAILABLE or SOLD_OUT");
        return s;
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
