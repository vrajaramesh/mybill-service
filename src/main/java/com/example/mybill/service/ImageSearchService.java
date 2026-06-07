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

@Service
public class ImageSearchService {

    @Autowired private DataSource dataSource;
    @Autowired private EmbeddingService embeddingService;

    /**
     * Find generated product images that best match the text query.
     * Uses FashionCLIP text embedding → cosine similarity against per-image embeddings.
     * Falls back to OpenAI text-embedding-3-small if FashionCLIP is unavailable.
     */
    public List<ImageSearchResult> search(String query, String schema, int limit) {
        float[] embedding = embeddingService.embed(query);
        String vec = embeddingService.toVectorString(embedding);

        String sql = """
            SELECT pie.product_id, p.product_name, pie.image_url,
                   pie.garment_type, pie.occasion, pie.description,
                   1 - (pie.embedding <=> CAST(? AS vector)) AS similarity
            FROM product_image_embeddings pie
            JOIN products p ON p.product_id = pie.product_id
            WHERE p.is_active = TRUE
              AND pie.embedding IS NOT NULL
              AND 1 - (pie.embedding <=> CAST(? AS vector)) > 0.20
            ORDER BY pie.embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

        List<ImageSearchResult> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, vec);
                ps.setString(2, vec);
                ps.setString(3, vec);
                ps.setInt(4, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ImageSearchResult r = new ImageSearchResult();
                        r.setProductId(rs.getInt("product_id"));
                        r.setProductName(rs.getString("product_name"));
                        r.setImageUrl(rs.getString("image_url"));
                        r.setGarmentType(rs.getString("garment_type"));
                        r.setOccasion(rs.getString("occasion"));
                        r.setDescription(rs.getString("description"));
                        r.setSimilarity(rs.getDouble("similarity"));
                        results.add(r);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Image search failed: " + e.getMessage(), e);
        }

        results.forEach(r -> System.err.printf(
            "[IMG-SEARCH] id=%-6d sim=%.3f garment=%-12s url=%s%n",
            r.getProductId(), r.getSimilarity(), r.getGarmentType(), r.getImageUrl()));

        return results;
    }

    /**
     * Convenience overload that uses the current TenantContext schema.
     */
    public List<ImageSearchResult> search(String query, int limit) {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null) schema = "public";
        return search(query, schema, limit);
    }
}
