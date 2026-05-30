package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Service
public class ChatService {

    @Autowired private DataSource dataSource;
    @Autowired private ProductVectorService productVectorService;
    @Autowired private GroqChatService groqChatService;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private GeminiService geminiService;
    @Autowired private ImageUploadService imageUploadService;

    private final ObjectMapper json = new ObjectMapper();

    // ── Public API ─────────────────────────────────────────────────────────────

    public ChatResponse chat(String schemaName, String firmName,
                             String sessionId, String userMessage, String ecomUrl,
                             String imageBase64, String imageMimeType) {
        return chat(schemaName, firmName, sessionId, userMessage, ecomUrl, imageBase64, imageMimeType, "web");
    }

    public ChatResponse chat(String schemaName, String firmName,
                             String sessionId, String userMessage, String ecomUrl,
                             String imageBase64, String imageMimeType, String channel) {
        ensureChatTablesExist(schemaName);

        // 1. Resolve session — create new or upsert existing (covers WhatsApp UUID sessions)
        String sid;
        if (sessionId != null && !sessionId.isBlank()) {
            sid = sessionId;
            upsertSession(schemaName, sid, channel != null ? channel : "web");
        } else {
            sid = createSession(schemaName, channel != null ? channel : "web");
        }

        // 2. Fetch history BEFORE storing current message
        List<Map<String, String>> history = fetchHistory(schemaName, sid, 10);

        // 3. Upload image to Cloudinary (non-fatal) so it appears in the report
        boolean imageSearch = imageBase64 != null && !imageBase64.isBlank();
        String uploadedImageUrl = null;
        if (imageSearch) {
            try {
                uploadedImageUrl = imageUploadService.uploadBase64(imageBase64, imageMimeType);
                System.err.println("[CHAT] Image uploaded: " + uploadedImageUrl);
            } catch (Exception e) {
                System.err.println("[CHAT] Cloudinary upload skipped: " + e.getMessage());
            }
        }

        // 4. Store user message (with image URL if available)
        String displayMessage = (userMessage != null && !userMessage.isBlank())
            ? userMessage : "Find products similar to this image";
        saveMessage(schemaName, sid, "user", displayMessage, null, uploadedImageUrl);

        // 5. Find matching products — image search takes priority over text search
        List<ProductSearchResult> products = new ArrayList<>();

        if (imageSearch) {
            // FashionCLIP image encoder → vector search
            try {
                TenantContext.setCurrentTenant(schemaName);
                float[] imageEmbedding = embeddingService.embedImageBase64(imageBase64, imageMimeType);
                products = productVectorService.searchByEmbedding(imageEmbedding, 8);
                System.err.println("[CHAT] Image vector search returned " + products.size() + " products");
            } catch (Exception e) {
                System.err.println("[CHAT] Image search failed: " + e.getMessage());
            } finally {
                TenantContext.clear();
            }

            // Fallback: if image vector search returned nothing, describe the image with Groq
            // vision and use that description for text search — works without pre-embedded products
            if (products.isEmpty()) {
                System.err.println("[CHAT] Image vector search returned 0 — using vision description fallback");
                String imageDesc = null;
                try {
                    imageDesc = geminiService.analyzeProductImage(imageBase64, imageMimeType, "", "");
                    System.err.println("[CHAT] Vision description: " + imageDesc);
                } catch (Exception e) {
                    System.err.println("[CHAT] Vision description failed: " + e.getMessage());
                }
                String searchText = (imageDesc != null && !imageDesc.isBlank()) ? imageDesc
                    : (userMessage != null && !userMessage.isBlank() ? userMessage : null);
                if (searchText != null) {
                    try {
                        TenantContext.setCurrentTenant(schemaName);
                        products = productVectorService.searchByText(searchText, 8);
                        System.err.println("[CHAT] Vision text-vector fallback returned " + products.size() + " products");
                    } catch (Exception e) {
                        System.err.println("[CHAT] Vision text-vector fallback failed: " + e.getMessage());
                    } finally {
                        TenantContext.clear();
                    }
                    if (products.isEmpty()) {
                        products = textSearchProducts(schemaName, searchText, 8);
                        System.err.println("[CHAT] Vision keyword fallback returned " + products.size() + " products");
                    }
                }
            }
        } else {
            // Text: vector search → text search fallback
            try {
                TenantContext.setCurrentTenant(schemaName);
                products = productVectorService.searchByText(userMessage, 8);
            } catch (Exception e) {
                System.err.println("[CHAT] Vector search skipped: " + e.getMessage());
            } finally {
                TenantContext.clear();
            }
            System.err.println("[CHAT] Vector search returned " + products.size() + " products");
            if (products.isEmpty()) {
                products = textSearchProducts(schemaName, userMessage, 8);
                System.err.println("[CHAT] Text search returned " + products.size() + " products");
            }
            if (products.size() > 8) products = products.subList(0, 8);
        }

        // 6. Call Groq with history + product context
        String reply;
        try {
            reply = groqChatService.chat(firmName, userMessage, history, products, ecomUrl);
        } catch (Exception e) {
            System.err.println("[CHAT] Groq call failed: " + e.getMessage());
            reply = "Sorry, I'm having trouble responding right now. Please try again in a moment.";
        }

        // 7. Serialize products shown and store assistant reply
        List<Map<String, Object>> productData = toProductData(products);
        String productsJson = null;
        try {
            if (!productData.isEmpty()) productsJson = json.writeValueAsString(productData);
        } catch (JsonProcessingException ignored) {}
        saveMessage(schemaName, sid, "assistant", reply, productsJson, null);
        updateSession(schemaName, sid);

        return new ChatResponse(sid, reply, productData);
    }

    // ── Text-based product search (fallback when vector search returns nothing) ──

    private static final Set<String> STOP_WORDS = Set.of(
        "i", "me", "my", "send", "give", "show", "get", "can", "you", "the", "a",
        "an", "is", "are", "for", "of", "in", "to", "want", "need", "have", "please",
        "some", "any", "all", "and", "or", "with", "this", "that", "what", "which",
        "do", "does", "did", "will", "would", "could", "should", "fabric", "fabrics",
        "cloth", "cloths", "pics", "pic", "photo", "photos", "image", "images",
        "detail", "details", "info", "about", "looking", "interested"
    );

    private List<ProductSearchResult> textSearchProducts(String schemaName, String userMessage, int limit) {
        String[] words = userMessage.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) keywords.add(w);
        }
        System.err.println("[CHAT] Text search keywords: " + keywords);
        if (keywords.isEmpty()) return Collections.emptyList();

        boolean hasIsOnline = columnExists(schemaName, "products", "is_online");
        String isOnlineFilter = hasIsOnline ? " AND p.is_online = TRUE" : "";

        // Each keyword matched across name, category, tags, description, suitable_for
        // Try AND first (precise), fall back to OR (broader) if no results
        for (String joinOp : new String[]{"AND", "OR"}) {
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) where.append(" ").append(joinOp).append(" ");
                where.append("(p.product_name ILIKE ? OR p.category ILIKE ?" +
                             " OR p.tags ILIKE ? OR p.description ILIKE ? OR p.suitable_for ILIKE ?)");
            }

            // CTE so image_url is available in ORDER BY without repeating the subquery
            String sql = "WITH pd AS (" +
                         " SELECT p.product_id, p.product_name, p.selling_price, p.tags, p.category," +
                         "  (SELECT i.image_url FROM product_images i" +
                         "   WHERE i.product_id = p.product_id ORDER BY i.created_at ASC LIMIT 1) AS image_url" +
                         " FROM products p" +
                         " LEFT JOIN product_category pc ON pc.category_name = p.category" +
                         " WHERE p.is_active = TRUE" +
                         " AND p.stock_quantity > 0" +
                         " AND (pc.is_online = TRUE OR p.category IS NULL)" +
                         isOnlineFilter +
                         " AND (" + where + ")" +
                         ") SELECT * FROM pd" +
                         " ORDER BY" +
                         "  CASE WHEN product_name ILIKE ? THEN 0 ELSE 1 END," + // exact name match first
                         "  CASE WHEN image_url IS NOT NULL THEN 0 ELSE 1 END," + // products with images first
                         "  product_name" +
                         " LIMIT ?";

            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SET search_path TO \"" + schemaName + "\", public");
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int idx = 1;
                    for (String kw : keywords) {
                        String like = "%" + kw + "%";
                        ps.setString(idx++, like); // product_name
                        ps.setString(idx++, like); // category
                        ps.setString(idx++, like); // tags
                        ps.setString(idx++, like); // description
                        ps.setString(idx++, like); // suitable_for
                    }
                    ps.setString(idx++, "%" + String.join("%", keywords) + "%");
                    ps.setInt(idx, limit);
                    List<ProductSearchResult> results = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ProductSearchResult r = new ProductSearchResult();
                            r.setProductId(rs.getInt("product_id"));
                            r.setProductName(rs.getString("product_name"));
                            r.setSellingPrice(rs.getBigDecimal("selling_price"));
                            r.setCategoryName(rs.getString("category"));
                            r.setTags(rs.getString("tags"));
                            r.setFirstImageUrl(rs.getString("image_url"));
                            results.add(r);
                        }
                    }
                    if (!results.isEmpty()) {
                        System.err.println("[CHAT] Text search (" + joinOp + ") found " + results.size() + " products");
                        return results;
                    }
                }
            } catch (Exception e) {
                System.err.println("[CHAT] Text search (" + joinOp + ") failed: " + e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private boolean columnExists(String schema, String table, String column) {
        String sql = "SELECT EXISTS (SELECT 1 FROM information_schema.columns " +
                     "WHERE table_schema = ? AND table_name = ? AND column_name = ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getBoolean(1);
        } catch (Exception e) {
            return false;
        }
    }

    // ── DB helpers ─────────────────────────────────────────────────────────────

    private void ensureChatTablesExist(String schema) {
        try (Connection c = dataSource.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("SET search_path TO \"" + schema + "\", public");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        session_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                        created_at    TIMESTAMP   DEFAULT NOW(),
                        last_active   TIMESTAMP   DEFAULT NOW(),
                        message_count INTEGER     DEFAULT 0
                    )""");
                s.execute("ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS channel VARCHAR(20) DEFAULT 'web'");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        message_id     SERIAL      PRIMARY KEY,
                        session_id     UUID        NOT NULL
                            REFERENCES chat_sessions(session_id) ON DELETE CASCADE,
                        role           VARCHAR(10) NOT NULL,
                        content        TEXT        NOT NULL,
                        products_shown JSONB,
                        created_at     TIMESTAMP   DEFAULT NOW()
                    )""");
                s.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS image_url VARCHAR(500)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_chat_msg_session " +
                          "ON chat_messages(session_id, created_at)");
            }
        } catch (Exception e) {
            System.err.println("[CHAT] Table setup failed: " + e.getMessage());
        }
    }

    private String createSession(String schema, String channel) {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            // Try with channel column; fall back to without (for schemas not yet migrated)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_sessions (channel) VALUES (?) RETURNING session_id::text")) {
                ps.setString(1, channel);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString(1);
            } catch (Exception e1) {
                try (PreparedStatement ps2 = c.prepareStatement(
                        "INSERT INTO chat_sessions DEFAULT VALUES RETURNING session_id::text")) {
                    ResultSet rs = ps2.executeQuery();
                    if (rs.next()) return rs.getString(1);
                }
            }
        } catch (Exception e) {
            System.err.println("[CHAT] Session create failed: " + e.getMessage());
        }
        return UUID.randomUUID().toString();
    }

    private void upsertSession(String schema, String sessionId, String channel) {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            // Try with channel column; fall back to without
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_sessions (session_id, channel) VALUES (?::uuid, ?) " +
                    "ON CONFLICT (session_id) DO NOTHING")) {
                ps.setString(1, sessionId);
                ps.setString(2, channel);
                ps.executeUpdate();
            } catch (Exception e1) {
                try (PreparedStatement ps2 = c.prepareStatement(
                        "INSERT INTO chat_sessions (session_id) VALUES (?::uuid) " +
                        "ON CONFLICT (session_id) DO NOTHING")) {
                    ps2.setString(1, sessionId);
                    ps2.executeUpdate();
                }
            }
        } catch (Exception e) {
            System.err.println("[CHAT] Session upsert failed: " + e.getMessage());
        }
    }

    private List<Map<String, String>> fetchHistory(String schema, String sessionId, int limit) {
        String sql = "SELECT role, content FROM chat_messages " +
                     "WHERE session_id = ? ORDER BY created_at DESC LIMIT ?";
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, UUID.fromString(sessionId));
                ps.setInt(2, limit);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("role",    rs.getString("role"));
                    m.put("content", rs.getString("content"));
                    rows.add(m);
                }
            }
        } catch (Exception e) {
            System.err.println("[CHAT] History fetch failed: " + e.getMessage());
        }
        Collections.reverse(rows); // chronological order for Claude
        return rows;
    }

    private void saveMessage(String schema, String sessionId, String role,
                             String content, String productsJson, String imageUrl) {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_messages (session_id, role, content, products_shown, image_url) " +
                    "VALUES (?, ?, ?, CAST(? AS jsonb), ?)")) {
                ps.setObject(1, UUID.fromString(sessionId));
                ps.setString(2, role);
                ps.setString(3, content);
                ps.setString(4, productsJson);
                ps.setString(5, imageUrl);
                ps.executeUpdate();
            } catch (Exception e1) {
                // Fallback: image_url column may not exist yet on this schema
                System.err.println("[CHAT] Retrying save without image_url: " + e1.getMessage());
                try (PreparedStatement ps2 = c.prepareStatement(
                        "INSERT INTO chat_messages (session_id, role, content, products_shown) " +
                        "VALUES (?, ?, ?, CAST(? AS jsonb))")) {
                    ps2.setObject(1, UUID.fromString(sessionId));
                    ps2.setString(2, role);
                    ps2.setString(3, content);
                    ps2.setString(4, productsJson);
                    ps2.executeUpdate();
                }
            }
        } catch (Exception e) {
            System.err.println("[CHAT] Message save failed: " + e.getMessage());
        }
    }

    private void updateSession(String schema, String sessionId) {
        String sql = "UPDATE chat_sessions " +
                     "SET last_active=NOW(), message_count=message_count+1 " +
                     "WHERE session_id=?";
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, UUID.fromString(sessionId));
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[CHAT] Session update failed: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> toProductData(List<ProductSearchResult> products) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ProductSearchResult p : products) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId",    p.getProductId());
            m.put("productName",  p.getProductName());
            m.put("sellingPrice", p.getSellingPrice());
            m.put("categoryName", p.getCategoryName());
            m.put("tags",         p.getTags());
            m.put("imageUrl",     p.getFirstImageUrl());
            list.add(m);
        }
        return list;
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    public static class ChatRequest {
        public String sessionId;
        public String message;
        public String ecomUrl;
        public String imageBase64;
        public String imageMimeType;
    }

    public static class ChatResponse {
        public final String sessionId;
        public final String reply;
        public final List<Map<String, Object>> products;

        public ChatResponse(String sessionId, String reply, List<Map<String, Object>> products) {
            this.sessionId = sessionId;
            this.reply     = reply;
            this.products  = products;
        }
    }
}
