package com.example.mybill.service;

import com.example.mybill.repository.FirmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * On startup, creates the ai_generation_prompt and product_model tables
 * in every firm schema if they don't already exist.
 * Safe to run on every boot (uses CREATE TABLE IF NOT EXISTS).
 */
@Component
@Order(10)
public class AiSchemaInitializer implements ApplicationRunner {

    @Autowired private DataSource dataSource;
    @Autowired private FirmRepository firmRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<String> schemas = firmRepository.findAll().stream()
            .map(f -> f.getSchemaName())
            .filter(s -> s != null && !s.isBlank())
            .toList();

        for (String schema : schemas) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                conn.createStatement().execute(
                    "SET search_path TO \"" + schema + "\", public");

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ai_generation_prompt (
                        id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        category     VARCHAR(100),
                        suitable_for VARCHAR(100) NOT NULL,
                        prompt       TEXT NOT NULL,
                        created_at   TIMESTAMPTZ DEFAULT now(),
                        updated_at   TIMESTAMPTZ DEFAULT now(),
                        UNIQUE (category, suitable_for)
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS product_model (
                        id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        garment_type    VARCHAR(100) NOT NULL UNIQUE,
                        model_name      VARCHAR(200),
                        model_image_url TEXT NOT NULL,
                        created_at      TIMESTAMPTZ DEFAULT now(),
                        updated_at      TIMESTAMPTZ DEFAULT now()
                    )
                    """);

                // Fix product_embeddings dimension for any schema still on 1536 (OpenAI) or 512 (old FashionCLIP)
                stmt.execute("""
                    DO $mig$
                    DECLARE col_mod integer;
                    BEGIN
                        SELECT atttypmod INTO col_mod
                        FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE c.relname = 'product_embeddings'
                          AND a.attname = 'embedding'
                          AND n.nspname = current_schema();
                        IF col_mod IS NOT NULL AND col_mod <> 768 THEN
                            TRUNCATE product_embeddings;
                            ALTER TABLE product_embeddings ALTER COLUMN embedding TYPE vector(768);
                        END IF;
                    END $mig$;
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS product_image_embeddings (
                        id           SERIAL      PRIMARY KEY,
                        product_id   INTEGER     NOT NULL,
                        image_url    TEXT        NOT NULL,
                        garment_type VARCHAR(100),
                        occasion     VARCHAR(200),
                        color_desc   VARCHAR(500),
                        description  TEXT,
                        embedding    vector(768),
                        created_at   TIMESTAMP   DEFAULT NOW(),
                        CONSTRAINT uq_pie_product_image UNIQUE (product_id, image_url)
                    )
                    """);

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_pie_product_id
                        ON product_image_embeddings(product_id)
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS product_marketing_content (
                        product_id           INTEGER PRIMARY KEY,
                        instagram_caption    TEXT,
                        whatsapp_text        TEXT,
                        hashtags             TEXT,
                        seo_description      TEXT,
                        generated_at         TIMESTAMP,
                        updated_at           TIMESTAMP
                    )
                    """);

                // Fix any prompts saved with empty-string category (should be NULL for catch-all matching)
                stmt.execute("""
                    UPDATE ai_generation_prompt SET category = NULL WHERE category = ''
                    """);

                // Seed default AI generation prompts only if table is empty.
                // Skipped if firm already has custom prompts configured.
                stmt.execute("""
                    DO $seed$
                    BEGIN
                      IF NOT EXISTS (SELECT 1 FROM ai_generation_prompt LIMIT 1) THEN
                        INSERT INTO ai_generation_prompt (suitable_for, category, prompt) VALUES
                        ('Saree',     NULL, 'Elegant Indian woman (age 25-32) wearing this fabric draped as a classic Nivi-style saree with neat pleats. Traditional gold jewellery, bindi. Heritage haveli courtyard with carved stone pillars.'),
                        ('Kurti',     NULL, 'Stylish Indian woman (age 22-30) wearing this fabric stitched as a straight-cut kurti with matching dupatta. Hair tied neatly. Clean studio backdrop with soft bokeh.'),
                        ('Dress',     NULL, 'Confident Indian woman (age 22-30) wearing this fabric as an elegant A-line midi dress. Minimal jewellery, bindi. Soft natural backlight from a garden window.'),
                        ('Frock',     NULL, '5-year-old cute Indian girl wearing this fabric as a flared frock with frill detailing. Cheerful expression, playful pose, pigtail hair. Bright sunlit garden with flowers.'),
                        ('Blouse',    NULL, 'Indian woman model wearing this fabric as a fitted saree blouse with square neck. Close-up portrait showing blouse embellishment detail. Complementary contrast saree, studio lighting.'),
                        ('Lehenga',   NULL, 'Indian woman or bride in full traditional look wearing this fabric as a lehenga skirt with blouse and dupatta. Heavy bridal jewellery, bindi. Decorated outdoor mandap with marigolds and fairy lights.'),
                        ('Salwar',    NULL, 'Indian woman (age 22-30) wearing this fabric as a straight salwar kameez with dupatta draped over one shoulder. Subtle jewellery, bindi. Clean minimal studio background.'),
                        ('Dupatta',   NULL, 'Indian woman wearing this fabric as a dupatta gracefully draped over a contrasting ethnic outfit. Movement caught mid-flow. Soft window light, indoor traditional setting.'),
                        ('Kids Wear', NULL, 'Happy Indian child (age 5-8) wearing this fabric as a colourful kids outfit. Cheerful natural smile, playful outdoor garden setting, bright soft natural light.');
                      END IF;
                    END $seed$;
                    """);

                System.out.println("[AiSchemaInitializer] Tables ready in schema: " + schema);

            } catch (Exception e) {
                System.err.println("[AiSchemaInitializer] Failed for schema " + schema + ": " + e.getMessage());
            }
        }
    }
}
