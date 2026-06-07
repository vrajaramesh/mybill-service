package com.example.mybill.service;

import com.example.mybill.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ImageEmbeddingService {

    @Autowired private DataSource dataSource;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private FashionCLIPService fashionCLIP;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /**
     * Embed and store all generated photos asynchronously.
     * Must be called while TenantContext is still set (captures it before async boundary).
     */
    public void embedAllAsync(Integer productId, List<PhotoResult> photos, String schema) {
        executor.submit(() -> {
            TenantContext.setCurrentTenant(schema);
            try {
                for (PhotoResult photo : photos) {
                    embedOne(productId, photo, schema);
                }
            } finally {
                TenantContext.clear();
            }
        });
    }

    /**
     * Called when a photo is manually uploaded via the Media Upload screen.
     * Uses GPT-4o to identify the garment type, then embeds and stores.
     * Fire-and-forget — does not block the HTTP response.
     */
    public void embedUploadedImageAsync(Integer productId, String imageUrl, String schema) {
        executor.submit(() -> {
            TenantContext.setCurrentTenant(schema);
            try {
                String garmentType = "Fabric";
                if (fashionCLIP.isAvailable()) {
                    try {
                        garmentType = fashionCLIP.classifyGarmentType(imageUrl);
                    } catch (Exception e) {
                        System.err.println("[IMG-EMBED] Garment classify failed, defaulting to Fabric: " + e.getMessage());
                    }
                }
                PhotoResult photo = new PhotoResult(imageUrl, garmentType, "uploaded", null);
                embedOne(productId, photo, schema);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void embedOne(Integer productId, PhotoResult photo, String schema) {
        try {
            String richText = buildRichText(photo);
            float[] embedding = embeddingService.embedImageUrl(photo.url(), richText);
            store(productId, photo, embedding, schema);
            System.out.println("[IMG-EMBED] Stored for product=" + productId
                + " garment=" + photo.garmentType() + " occasion=" + photo.occasion());
        } catch (Exception e) {
            System.err.println("[IMG-EMBED] Failed for " + photo.url() + ": " + e.getMessage());
        }
    }

    private void store(Integer productId, PhotoResult photo, float[] embedding, String schema) {
        String vec = embeddingService.toVectorString(embedding);
        String sql = """
            INSERT INTO product_image_embeddings
                (product_id, image_url, garment_type, occasion, color_desc, description, embedding)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector))
            ON CONFLICT (product_id, image_url) DO UPDATE
              SET garment_type = EXCLUDED.garment_type,
                  occasion     = EXCLUDED.occasion,
                  color_desc   = EXCLUDED.color_desc,
                  description  = EXCLUDED.description,
                  embedding    = EXCLUDED.embedding,
                  created_at   = NOW()
            """;
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET search_path TO \"" + schema + "\", public");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, productId);
                ps.setString(2, photo.url());
                ps.setString(3, photo.garmentType());
                ps.setString(4, photo.occasion());
                String desc = photo.description();
                ps.setString(5, desc != null && desc.length() > 300 ? desc.substring(0, 300) : desc);
                ps.setString(6, desc);
                ps.setString(7, vec);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store image embedding: " + e.getMessage(), e);
        }
    }

    private String buildRichText(PhotoResult photo) {
        StringBuilder sb = new StringBuilder();
        if (photo.garmentType() != null) sb.append("Garment: ").append(photo.garmentType()).append(". ");
        if (photo.occasion() != null) sb.append("Occasion: ").append(photo.occasion()).append(". ");
        if (photo.description() != null) sb.append(photo.description());
        return sb.toString().trim();
    }
}
