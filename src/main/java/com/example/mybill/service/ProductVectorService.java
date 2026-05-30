package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class ProductVectorService {

    @Autowired private DataSource dataSource;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private ProductService productService;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // ── Async trigger ─────────────────────────────────────────────────────────

    public void triggerEmbeddingAsync(Integer productId, String imageUrl) {
        String tenant = TenantContext.getCurrentTenant();
        executor.submit(() -> {
            TenantContext.setCurrentTenant(tenant);
            try {
                Optional<Product> opt = productService.getProductById(productId);
                if (opt.isEmpty()) return;
                Product p = opt.get();

                String metaText = buildMetaText(p);
                float[] embedding;
                if (imageUrl != null && !imageUrl.isBlank()) {
                    // FashionCLIP image encoder — captures visual design, color, pattern directly.
                    // No Gemini description needed; the image IS the signal.
                    embedding = embeddingService.embedImageUrl(imageUrl, metaText);
                } else {
                    // No image: embed structured text so text queries can still find the product
                    embedding = embeddingService.embed(metaText);
                }
                storeEmbedding(productId, embedding, metaText, tenant);
                System.err.println("[VECTOR] Embedded product " + productId
                    + (imageUrl != null ? " (image)" : " (text)"));

            } catch (Exception e) {
                System.err.println("[VECTOR] Embedding failed for product " + productId + ": " + e.getMessage());
            } finally {
                TenantContext.clear();
            }
        });
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    private void storeEmbedding(Integer productId, float[] embedding, String metaText, String schemaName) {
        String vec = embeddingService.toVectorString(embedding);
        String sql = """
            INSERT INTO product_embeddings (product_id, embedding, meta_text, updated_at)
            VALUES (?, CAST(? AS vector), ?, NOW())
            ON CONFLICT (product_id) DO UPDATE
            SET embedding = CAST(? AS vector), meta_text = ?, updated_at = NOW()
            """;
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO \"" + schemaName + "\", public");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, productId);
                ps.setString(2, vec);
                ps.setString(3, metaText);
                ps.setString(4, vec);
                ps.setString(5, metaText);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store embedding: " + e.getMessage(), e);
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public List<ProductSearchResult> searchByText(String query, int limit) {
        float[] embedding = embeddingService.embed(query);
        return search(embedding, limit);
    }

    public List<ProductSearchResult> searchByEmbedding(float[] embedding, int limit) {
        return search(embedding, limit);
    }

    public List<ProductSearchResult> searchByImage(String base64Image, String mimeType) {
        float[] embedding = embeddingService.embedImageBase64(base64Image, mimeType);
        return search(embedding, 10);
    }

    private List<ProductSearchResult> search(float[] queryEmbedding, int limit) {
        String schemaName = TenantContext.getCurrentTenant();
        if (schemaName == null) schemaName = "public";
        String vec = embeddingService.toVectorString(queryEmbedding);

        String sql = """
            SELECT p.product_id, p.product_name, p.selling_price, p.tags,
                   pc.category_name,
                   (SELECT i.image_url FROM product_images i
                    WHERE i.product_id = p.product_id
                    ORDER BY i.created_at ASC LIMIT 1) AS image_url,
                   1 - (pe.embedding <=> CAST(? AS vector)) AS similarity
            FROM product_embeddings pe
            JOIN products p ON p.product_id = pe.product_id
            LEFT JOIN product_category pc ON pc.category_name = p.category
            WHERE p.is_active = TRUE
              AND p.stock_quantity > 0
              AND (pc.is_online = TRUE OR p.category IS NULL)
              AND 1 - (pe.embedding <=> CAST(? AS vector)) > 0.15
            ORDER BY pe.embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

        List<ProductSearchResult> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO \"" + schemaName + "\", public");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, vec);
                ps.setString(2, vec);
                ps.setString(3, vec);
                ps.setInt(4, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ProductSearchResult r = new ProductSearchResult();
                        r.setProductId(rs.getInt("product_id"));
                        r.setProductName(rs.getString("product_name"));
                        r.setSellingPrice(rs.getBigDecimal("selling_price"));
                        r.setCategoryName(rs.getString("category_name"));
                        r.setTags(rs.getString("tags"));
                        r.setFirstImageUrl(rs.getString("image_url"));
                        r.setSimilarity(rs.getDouble("similarity"));
                        results.add(r);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Vector search failed: " + e.getMessage(), e);
        }
        results.forEach(r -> System.err.printf(
            "[VECTOR] id=%-6d similarity=%.3f name=%s%n",
            r.getProductId(), r.getSimilarity(), r.getProductName()));
        return results;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildMetaText(Product p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Product Name: ").append(p.getProductName()).append("\n");
        if (p.getCategory() != null)
            sb.append("Category: ").append(p.getCategory().getCategoryName()).append("\n");
        if (p.getSubCategory() != null)
            sb.append("Sub-category: ").append(p.getSubCategory().getSubCatName()).append("\n");
        if (p.getTags() != null && !p.getTags().isBlank())
            sb.append("Occasions/Style: ").append(p.getTags()).append("\n");
        if (p.getSuitableFor() != null && !p.getSuitableFor().isBlank())
            sb.append("Suitable For: ").append(p.getSuitableFor()).append("\n");
        if (p.getDescription() != null && !p.getDescription().isBlank())
            sb.append("Description: ").append(p.getDescription()).append("\n");
        return sb.toString().trim();
    }
}
