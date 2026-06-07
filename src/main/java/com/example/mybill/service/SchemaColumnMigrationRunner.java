package com.example.mybill.service;

import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies idempotent ALTER TABLE migrations to all existing firm schemas on startup.
 * Run after SuperadminInitializer (Order 2 vs default Order).
 */
@Component
@Order(2)
public class SchemaColumnMigrationRunner implements ApplicationRunner {

    @Autowired private FirmRepository firmRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Firm> firms = firmRepository.findAll();
        for (Firm firm : firms) {
            String s = firm.getSchemaName();
            try {
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".bills ADD COLUMN IF NOT EXISTS sales_person_id   INTEGER");
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".bills ADD COLUMN IF NOT EXISTS sales_person_name VARCHAR(100)");
            } catch (Exception e) {
                System.err.println("[Migration] Could not migrate bills for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".products ADD COLUMN IF NOT EXISTS sizes TEXT");
            } catch (Exception e) {
                System.err.println("[Migration] Could not add sizes column for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".products ADD COLUMN IF NOT EXISTS sub_category_id INTEGER");
            } catch (Exception e) {
                System.err.println("[Migration] Could not add sub_category_id column for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".products ADD COLUMN IF NOT EXISTS is_online BOOLEAN NOT NULL DEFAULT TRUE");
            } catch (Exception e) {
                System.err.println("[Migration] Could not add is_online column for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute(
                    "DO $$ BEGIN " +
                    "  IF EXISTS (SELECT 1 FROM information_schema.tables " +
                    "             WHERE table_schema='" + s + "' AND table_name='chat_sessions') THEN " +
                    "    ALTER TABLE \"" + s + "\".chat_sessions ADD COLUMN IF NOT EXISTS channel VARCHAR(20) DEFAULT 'web'; " +
                    "  END IF; " +
                    "END $$");
            } catch (Exception e) {
                System.err.println("[Migration] chat_sessions channel column: " + e.getMessage());
            }
            try {
                jdbcTemplate.execute(
                    "DO $$ BEGIN " +
                    "  IF EXISTS (SELECT 1 FROM information_schema.tables " +
                    "             WHERE table_schema='" + s + "' AND table_name='chat_messages') THEN " +
                    "    ALTER TABLE \"" + s + "\".chat_messages ADD COLUMN IF NOT EXISTS image_url VARCHAR(500); " +
                    "  END IF; " +
                    "END $$");
            } catch (Exception e) {
                System.err.println("[Migration] chat_messages image_url column: " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS "%s".ecom_customers (
                        customer_id   SERIAL       PRIMARY KEY,
                        name          VARCHAR(100) NOT NULL,
                        email         VARCHAR(150) NOT NULL UNIQUE,
                        phone         VARCHAR(20),
                        password_hash VARCHAR(200) NOT NULL,
                        is_active     BOOLEAN      DEFAULT TRUE,
                        created_at    TIMESTAMP    DEFAULT NOW()
                    )""".formatted(s));
            } catch (Exception e) {
                System.err.println("[Migration] Could not create ecom_customers for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS "%s".cart_items (
                        item_id     SERIAL        PRIMARY KEY,
                        customer_id INTEGER       NOT NULL REFERENCES "%s".ecom_customers(customer_id) ON DELETE CASCADE,
                        product_id  INTEGER       NOT NULL,
                        quantity    NUMERIC(10,3) NOT NULL DEFAULT 1,
                        size        VARCHAR(10),
                        created_at  TIMESTAMP     DEFAULT NOW(),
                        UNIQUE(customer_id, product_id, size)
                    )""".formatted(s, s));
            } catch (Exception e) {
                System.err.println("[Migration] Could not create cart_items for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS "%s".ecom_orders (
                        order_id            SERIAL        PRIMARY KEY,
                        customer_id         INTEGER       NOT NULL REFERENCES "%s".ecom_customers(customer_id),
                        razorpay_order_id   VARCHAR(100)  UNIQUE,
                        razorpay_payment_id VARCHAR(100),
                        amount              NUMERIC(10,2) NOT NULL,
                        status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                        delivery_name       VARCHAR(100),
                        delivery_phone      VARCHAR(20),
                        delivery_email      VARCHAR(150),
                        delivery_address    TEXT,
                        delivery_city       VARCHAR(100),
                        delivery_pincode    VARCHAR(10),
                        notes               TEXT,
                        created_at          TIMESTAMP     DEFAULT NOW(),
                        paid_at             TIMESTAMP
                    )""".formatted(s, s));
            } catch (Exception e) {
                System.err.println("[Migration] Could not create ecom_orders for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS "%s".ecom_order_items (
                        id           SERIAL        PRIMARY KEY,
                        order_id     INTEGER       NOT NULL REFERENCES "%s".ecom_orders(order_id) ON DELETE CASCADE,
                        product_id   INTEGER       NOT NULL,
                        product_name VARCHAR(200),
                        unit         VARCHAR(20),
                        size         VARCHAR(10),
                        quantity     NUMERIC(10,3) NOT NULL,
                        unit_price   NUMERIC(10,2) NOT NULL,
                        total_price  NUMERIC(10,2) NOT NULL
                    )""".formatted(s, s));
            } catch (Exception e) {
                System.err.println("[Migration] Could not create ecom_order_items for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS "%s".boutique_designs (
                        design_id     SERIAL        PRIMARY KEY,
                        garment_type  VARCHAR(100)  NOT NULL,
                        description   TEXT,
                        rough_price   NUMERIC(10,2),
                        delivery_days INTEGER,
                        image_url     VARCHAR(500),
                        public_id     VARCHAR(255),
                        is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
                        created_at    TIMESTAMP     DEFAULT NOW(),
                        updated_at    TIMESTAMP
                    )""".formatted(s));
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".boutique_designs ADD COLUMN IF NOT EXISTS delivery_days INTEGER");
            } catch (Exception e) {
                System.err.println("[Migration] Could not create boutique_designs for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS "%s".boutique_design_images (
                        id         SERIAL       PRIMARY KEY,
                        design_id  INTEGER      NOT NULL,
                        image_url  VARCHAR(500) NOT NULL,
                        public_id  VARCHAR(255),
                        created_at TIMESTAMP    DEFAULT NOW()
                    )""".formatted(s));
            } catch (Exception e) {
                System.err.println("[Migration] Could not create boutique_design_images for schema " + s + ": " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".ecom_orders ADD COLUMN IF NOT EXISTS admin_notes TEXT");
            } catch (Exception e) {
                System.err.println("[Migration] ecom_orders admin_notes: " + e.getMessage());
            }
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS "%s".firm_settings (
                        key   VARCHAR(100) PRIMARY KEY,
                        value TEXT
                    )""".formatted(s));
            } catch (Exception e) {
                System.err.println("[Migration] Could not create firm_settings for schema " + s + ": " + e.getMessage());
            }
            // product_images: new columns for ecom media management
            try {
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".product_images ADD COLUMN IF NOT EXISTS image_type  VARCHAR(20)");
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".product_images ADD COLUMN IF NOT EXISTS media_type  VARCHAR(10) DEFAULT 'image'");
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".product_images ADD COLUMN IF NOT EXISTS sort_order  INTEGER DEFAULT 0");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_product_images_sort ON \"" + s + "\".product_images (product_id, sort_order)");
            } catch (Exception e) {
                System.err.println("[Migration] product_images ecom columns for schema " + s + ": " + e.getMessage());
            }
            // products: new ecom columns
            try {
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".products ADD COLUMN IF NOT EXISTS suitable_for VARCHAR(500)");
                jdbcTemplate.execute("ALTER TABLE \"" + s + "\".products ADD COLUMN IF NOT EXISTS tags         VARCHAR(500)");
            } catch (Exception e) {
                System.err.println("[Migration] products ecom columns for schema " + s + ": " + e.getMessage());
            }
        }

        // Ensure admin_firm_access table exists (idempotent)
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.admin_firm_access (
                    id          SERIAL      PRIMARY KEY,
                    admin_id    INTEGER     NOT NULL REFERENCES public.app_users_public(user_id) ON DELETE CASCADE,
                    firm_id     BIGINT      NOT NULL REFERENCES public.firms(firm_id) ON DELETE CASCADE,
                    firm_code   VARCHAR(50) NOT NULL,
                    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
                    assigned_by VARCHAR(100),
                    created_at  TIMESTAMP   DEFAULT NOW(),
                    UNIQUE(admin_id, firm_id)
                )""");
        } catch (Exception e) {
            System.err.println("[Migration] admin_firm_access: " + e.getMessage());
        }
    }
}
