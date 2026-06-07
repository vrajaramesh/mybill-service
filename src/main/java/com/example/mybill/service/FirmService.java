package com.example.mybill.service;

import com.example.mybill.dto.Firm;
import com.example.mybill.dto.UserFirmAccess;
import com.example.mybill.multitenancy.TenantContext;
import com.example.mybill.repository.FirmRepository;
import com.example.mybill.repository.UserFirmAccessRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FirmService {

    @Autowired
    private FirmRepository firmRepository;

    @Autowired
    private UserFirmAccessRepository userFirmAccessRepository;

    @Autowired
    private RoleService roleService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Firm registerFirm(String firmName, String firmCode, String ownerEmail,
                             String adminUsername, String adminPassword, String adminFullName,
                             Integer superadminId) {

        // Verify that the caller is a superadmin
        if (!roleService.isSuperadminById(superadminId)) {
            throw new IllegalAccessError("Only superadmins can create firms");
        }

        String normalizedCode = firmCode.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        if (firmRepository.existsByFirmCode(normalizedCode)) {
            throw new IllegalArgumentException("Firm code already taken: " + normalizedCode);
        }

        String schemaName = "firm_" + normalizedCode;
        if (firmRepository.existsBySchemaName(schemaName)) {
            throw new IllegalArgumentException("Schema already exists: " + schemaName);
        }

        provisionSchema(schemaName, adminUsername, adminPassword, adminFullName);

        Firm firm = new Firm();
        firm.setFirmName(firmName);
        firm.setFirmCode(normalizedCode);
        firm.setSchemaName(schemaName);
        firm.setOwnerEmail(ownerEmail);
        firm.setSuperadminId(superadminId);
        firm.setIsActive(true);
        firm.setCreatedAt(LocalDateTime.now());
        Firm savedFirm = firmRepository.save(firm);

        // Automatically add admin user to user_firm_access with primary access
        Integer adminUserId = getAdminUserIdFromSchema(schemaName, adminUsername);
        if (adminUserId != null) {
            UserFirmAccess access = new UserFirmAccess();
            access.setUserId(adminUserId);
            access.setFirmId(savedFirm.getFirmId().intValue());
            access.setFirmCode(normalizedCode);
            access.setAccessLevel("ADMIN");
            access.setIsPrimary(true);
            access.setIsActive(true);
            access.setAssignedBy("SYSTEM");
            access.setCreatedAt(LocalDateTime.now());
            userFirmAccessRepository.save(access);
        }

        return savedFirm;
    }

    public Firm registerFirm(String firmName, String firmCode, String ownerEmail,
                             String adminUsername, String adminPassword, String adminFullName) {
        // Legacy method for backward compatibility - will fail if called without superadminId
        throw new IllegalAccessError("Firm registration requires superadmin authentication");
    }

    public List<Firm> listFirms() {
        return firmRepository.findAll();
    }

    private Integer getAdminUserIdFromSchema(String schemaName, String username) {
        try {
            String sql = "SELECT user_id FROM \"" + schemaName + "\".app_users WHERE username = ?";
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, username);
            if (!result.isEmpty()) {
                return ((Number) result.get(0).get("user_id")).intValue();
            }
        } catch (Exception e) {
            // Return null if user not found
        }
        return null;
    }

    private void provisionSchema(String schemaName, String adminUsername,
                                  String adminPassword, String adminFullName) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
                stmt.execute("SET search_path TO \"" + schemaName + "\"");
                createTables(stmt);
                seedCategories(stmt);
            }
            createAdminUser(conn, adminUsername, adminPassword, adminFullName);
            conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("Failed to provision schema: " + schemaName, e);
        }
    }

    private void createTables(Statement stmt) throws Exception {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS app_users (
                user_id       SERIAL       PRIMARY KEY,
                username      VARCHAR(50)  NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                full_name     VARCHAR(100),
                email         VARCHAR(100),
                phone         VARCHAR(20),
                role          VARCHAR(20)  NOT NULL DEFAULT 'SALES',
                is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
                created_at    TIMESTAMP,
                last_login_at TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS customers (
                customer_id   SERIAL       PRIMARY KEY,
                customer_name VARCHAR(100),
                phone         VARCHAR(15),
                address       TEXT,
                created_at    TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS suppliers (
                supplier_id    SERIAL       PRIMARY KEY,
                supplier_name  VARCHAR(200) NOT NULL,
                contact_person VARCHAR(100),
                phone          VARCHAR(20),
                email          VARCHAR(100),
                address        TEXT,
                created_at     TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS product_category (
                category_name VARCHAR(100) PRIMARY KEY,
                is_online     BOOLEAN      NOT NULL DEFAULT TRUE
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS product_sub_category (
                id            SERIAL      PRIMARY KEY,
                sub_cat_name  VARCHAR(100) NOT NULL,
                category_name VARCHAR(100) NOT NULL REFERENCES product_category(category_name) ON DELETE CASCADE,
                is_online     BOOLEAN      NOT NULL DEFAULT TRUE,
                UNIQUE (sub_cat_name, category_name)
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS products (
                product_id      INTEGER        PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
                product_name    VARCHAR(200)   NOT NULL,
                description     TEXT,
                category        VARCHAR(100)   REFERENCES product_category (category_name) ON DELETE SET NULL,
                unit            VARCHAR(20)    DEFAULT 'meter',
                sizes           TEXT,
                cost_price      NUMERIC(10,2),
                selling_price   NUMERIC(10,2)  NOT NULL,
                stock_quantity  NUMERIC(10,2)  NOT NULL DEFAULT 0,
                min_stock_level NUMERIC(10,2)  NOT NULL DEFAULT 0,
                sub_category_id INTEGER        REFERENCES product_sub_category(id) ON DELETE SET NULL,
                is_active       BOOLEAN        NOT NULL DEFAULT TRUE,
                is_online       BOOLEAN        NOT NULL DEFAULT TRUE,
                created_at      TIMESTAMP,
                updated_at      TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS product_images (
                image_id   SERIAL      PRIMARY KEY,
                product_id INTEGER     NOT NULL REFERENCES products (product_id) ON DELETE CASCADE,
                image_url  VARCHAR(500) NOT NULL,
                public_id  VARCHAR(255),
                image_type VARCHAR(20)  DEFAULT 'user',
                media_type VARCHAR(10)  DEFAULT 'image',
                created_at TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS product_sub_cat_map (
                product_id      INTEGER NOT NULL REFERENCES products(product_id) ON DELETE CASCADE,
                sub_category_id INTEGER NOT NULL REFERENCES product_sub_category(id) ON DELETE CASCADE,
                PRIMARY KEY (product_id, sub_category_id)
            )""");

        stmt.execute("ALTER TABLE products ADD COLUMN IF NOT EXISTS tags TEXT");
        stmt.execute("ALTER TABLE products ADD COLUMN IF NOT EXISTS suitable_for TEXT");
        stmt.execute("ALTER TABLE products ADD COLUMN IF NOT EXISTS sizes TEXT");
        stmt.execute("ALTER TABLE product_images ADD COLUMN IF NOT EXISTS media_type VARCHAR(10) DEFAULT 'image'");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS product_embeddings (
                product_id  INTEGER PRIMARY KEY REFERENCES products(product_id) ON DELETE CASCADE,
                embedding   VECTOR(768),
                meta_text   TEXT,
                updated_at  TIMESTAMP DEFAULT NOW()
            )""");
        // Migrate any prior dimension (3072 Gemini, 1536 OpenAI, or 512 old FashionCLIP) → 768 (FashionSigLIP)
        stmt.execute("""
            DO $mig$
            DECLARE col_mod integer;
            BEGIN
                SELECT atttypmod INTO col_mod
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = 'product_embeddings'
                  AND a.attname  = 'embedding'
                  AND n.nspname  = current_schema();
                IF col_mod IS NOT NULL AND col_mod <> 768 THEN
                    TRUNCATE product_embeddings;
                    ALTER TABLE product_embeddings ALTER COLUMN embedding TYPE vector(768);
                END IF;
            END $mig$;
            """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS bills (
                bill_id         SERIAL        PRIMARY KEY,
                bill_number     VARCHAR(30)   NOT NULL UNIQUE,
                bill_date       DATE          NOT NULL,
                customer_id     INTEGER       REFERENCES customers (customer_id) ON DELETE SET NULL,
                subtotal        NUMERIC(12,2) NOT NULL DEFAULT 0,
                gst_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,
                discount_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
                total_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
                payment_method     VARCHAR(10)   NOT NULL DEFAULT 'CASH',
                notes              TEXT,
                sales_person_id    INTEGER,
                sales_person_name  VARCHAR(100),
                created_at         TIMESTAMP
            )""");
        stmt.execute("ALTER TABLE bills ADD COLUMN IF NOT EXISTS sales_person_id   INTEGER");
        stmt.execute("ALTER TABLE bills ADD COLUMN IF NOT EXISTS sales_person_name VARCHAR(100)");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS bill_items (
                bill_item_id    SERIAL        PRIMARY KEY,
                bill_id         INTEGER       NOT NULL REFERENCES bills (bill_id) ON DELETE CASCADE,
                product_id      INTEGER       REFERENCES products (product_id) ON DELETE SET NULL,
                item_description TEXT,
                quantity        NUMERIC(10,2) NOT NULL,
                unit_price      NUMERIC(12,2) NOT NULL,
                discount_pct    NUMERIC(5,2)  NOT NULL DEFAULT 0,
                taxable_amount  NUMERIC(12,2) NOT NULL,
                gst_pct         NUMERIC(5,2)  NOT NULL DEFAULT 5,
                gst_amount      NUMERIC(12,2) NOT NULL,
                total_price     NUMERIC(12,2) NOT NULL
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS purchases (
                purchase_id      SERIAL        PRIMARY KEY,
                supplier_id      INTEGER       NOT NULL REFERENCES suppliers (supplier_id),
                invoice_number   VARCHAR(100)  NOT NULL,
                invoice_date     DATE          NOT NULL,
                total_amount     NUMERIC(12,2) NOT NULL,
                gst              NUMERIC(12,2),
                final_price      NUMERIC(12,2),
                paid_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,
                payment_status   VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                payment_due_date DATE,
                notes            TEXT,
                created_at       TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS purchase_items (
                purchase_item_id SERIAL        PRIMARY KEY,
                purchase_id      INTEGER       NOT NULL REFERENCES purchases (purchase_id) ON DELETE CASCADE,
                product_id       INTEGER       NOT NULL REFERENCES products (product_id),
                quantity         NUMERIC(10,2) NOT NULL,
                unit_price       NUMERIC(10,2) NOT NULL,
                total_price      NUMERIC(12,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,
                gst              NUMERIC(10,2),
                final_price      NUMERIC(12,2)
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS purchase_payments (
                payment_id     SERIAL        PRIMARY KEY,
                purchase_id    INTEGER       NOT NULL REFERENCES purchases (purchase_id) ON DELETE CASCADE,
                payment_date   DATE          NOT NULL,
                amount         NUMERIC(12,2) NOT NULL,
                payment_method VARCHAR(50),
                notes          TEXT
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS stitching_orders (
                order_id       SERIAL        PRIMARY KEY,
                order_number   VARCHAR(30)   UNIQUE,
                customer_id    INTEGER       REFERENCES customers (customer_id) ON DELETE SET NULL,
                order_date     DATE,
                delivery_date  DATE,
                priority       VARCHAR(20)   NOT NULL DEFAULT 'NORMAL',
                status         VARCHAR(30)   NOT NULL DEFAULT 'RECEIVED',
                total_amount   NUMERIC(10,2) NOT NULL DEFAULT 0,
                advance_paid   NUMERIC(10,2) NOT NULL DEFAULT 0,
                balance_amount NUMERIC(10,2) NOT NULL DEFAULT 0,
                notes          TEXT,
                created_at     TIMESTAMP,
                updated_at     TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS stitching_order_items (
                item_id              SERIAL      PRIMARY KEY,
                order_id             INTEGER     NOT NULL REFERENCES stitching_orders (order_id) ON DELETE CASCADE,
                garment_type         VARCHAR(50) NOT NULL,
                fabric_description   VARCHAR(200),
                quantity             INTEGER     NOT NULL DEFAULT 1,
                stitching_charges    NUMERIC(10,2) NOT NULL DEFAULT 0,
                special_instructions VARCHAR(500),
                item_status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
                chest NUMERIC(5,1), waist NUMERIC(5,1), hip NUMERIC(5,1),
                shoulder NUMERIC(5,1), sleeve_length NUMERIC(5,1), blouse_length NUMERIC(5,1),
                neck_front_depth NUMERIC(5,1), neck_back_depth NUMERIC(5,1), neck_width NUMERIC(5,1),
                kurta_length NUMERIC(5,1), salwar_length NUMERIC(5,1), full_length NUMERIC(5,1),
                armhole NUMERIC(5,1), upper_bust NUMERIC(5,1), under_bust NUMERIC(5,1),
                sleeve_round NUMERIC(5,1), bicep_round NUMERIC(5,1), elbow_round NUMERIC(5,1),
                wrist_round NUMERIC(5,1), apex_point NUMERIC(5,1), apex_to_apex NUMERIC(5,1),
                shoulder_to_apex NUMERIC(5,1), shoulder_to_under_bust NUMERIC(5,1),
                front_length NUMERIC(5,1), back_length NUMERIC(5,1), front_width NUMERIC(5,1),
                back_width NUMERIC(5,1), side_seam_length NUMERIC(5,1), strap_width NUMERIC(5,1),
                princess_line_length NUMERIC(5,1), cup_size_padding VARCHAR(100),
                bust_shape_observation VARCHAR(300), high_waist_round NUMERIC(5,1),
                low_waist_round NUMERIC(5,1), seat_round NUMERIC(5,1), thigh_round NUMERIC(5,1),
                knee_round NUMERIC(5,1), calf_round NUMERIC(5,1), bottom_round NUMERIC(5,1),
                waist_to_hip_length NUMERIC(5,1), waist_to_knee NUMERIC(5,1), slit_height NUMERIC(5,1),
                can_can_requirement VARCHAR(200), waist_finish VARCHAR(100), waist_gather VARCHAR(200),
                ankle_round NUMERIC(5,1), inseam_length NUMERIC(5,1), rise_length NUMERIC(5,1),
                crotch_depth NUMERIC(5,1), collar_round NUMERIC(5,1), front_slit_length NUMERIC(5,1),
                kali_width NUMERIC(5,1), number_of_kalis VARCHAR(50), trail_length NUMERIC(5,1),
                under_bust_belt_length NUMERIC(5,1), back_opening_length NUMERIC(5,1),
                waist_joint_length NUMERIC(5,1), kali_length NUMERIC(5,1),
                mid_thigh_round NUMERIC(5,1), front_rise NUMERIC(5,1), back_rise NUMERIC(5,1),
                waistband_width NUMERIC(5,1), boutique_observations VARCHAR(500)
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS boutique_payments (
                payment_id     SERIAL        PRIMARY KEY,
                order_id       INTEGER       NOT NULL REFERENCES stitching_orders (order_id) ON DELETE CASCADE,
                payment_date   DATE,
                amount         NUMERIC(10,2) NOT NULL,
                payment_method VARCHAR(20)   NOT NULL DEFAULT 'CASH',
                payment_type   VARCHAR(20)   NOT NULL DEFAULT 'ADVANCE',
                notes          VARCHAR(200),
                created_at     TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS boutique_extra_charges (
                charge_id   SERIAL        PRIMARY KEY,
                order_id    INTEGER       NOT NULL REFERENCES stitching_orders (order_id) ON DELETE CASCADE,
                description VARCHAR(255)  NOT NULL,
                amount      NUMERIC(10,2) NOT NULL DEFAULT 0,
                added_at    TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS boutique_order_images (
                image_id   SERIAL       PRIMARY KEY,
                item_id    INTEGER      NOT NULL REFERENCES stitching_order_items (item_id) ON DELETE CASCADE,
                image_url  VARCHAR(500) NOT NULL,
                public_id  VARCHAR(255),
                created_at TIMESTAMP
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS boutique_designs (
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
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS boutique_design_images (
                id         SERIAL       PRIMARY KEY,
                design_id  INTEGER      NOT NULL,
                image_url  VARCHAR(500) NOT NULL,
                public_id  VARCHAR(255),
                created_at TIMESTAMP    DEFAULT NOW()
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS firm_settings (
                key   VARCHAR(100) PRIMARY KEY,
                value TEXT
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS ecom_customers (
                customer_id   SERIAL       PRIMARY KEY,
                name          VARCHAR(100) NOT NULL,
                email         VARCHAR(150) NOT NULL UNIQUE,
                phone         VARCHAR(20),
                password_hash VARCHAR(200) NOT NULL,
                is_active     BOOLEAN      DEFAULT TRUE,
                created_at    TIMESTAMP    DEFAULT NOW()
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS cart_items (
                item_id     SERIAL        PRIMARY KEY,
                customer_id INTEGER       NOT NULL REFERENCES ecom_customers(customer_id) ON DELETE CASCADE,
                product_id  INTEGER       NOT NULL,
                quantity    NUMERIC(10,3) NOT NULL DEFAULT 1,
                size        VARCHAR(10),
                created_at  TIMESTAMP     DEFAULT NOW(),
                UNIQUE(customer_id, product_id, size)
            )""");

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS customer_measurements (
                measurement_id SERIAL      PRIMARY KEY,
                customer_id    INTEGER     NOT NULL REFERENCES customers (customer_id) ON DELETE CASCADE,
                profile_name   VARCHAR(100) NOT NULL DEFAULT 'Default',
                chest NUMERIC(5,1), waist NUMERIC(5,1), hip NUMERIC(5,1),
                shoulder NUMERIC(5,1), sleeve_length NUMERIC(5,1), armhole NUMERIC(5,1),
                blouse_length NUMERIC(5,1), neck_front_depth NUMERIC(5,1), neck_back_depth NUMERIC(5,1),
                neck_width NUMERIC(5,1), kurta_length NUMERIC(5,1), salwar_length NUMERIC(5,1),
                full_length NUMERIC(5,1), upper_bust NUMERIC(5,1), under_bust NUMERIC(5,1),
                sleeve_round NUMERIC(5,1), bicep_round NUMERIC(5,1), elbow_round NUMERIC(5,1),
                wrist_round NUMERIC(5,1), apex_point NUMERIC(5,1), apex_to_apex NUMERIC(5,1),
                shoulder_to_apex NUMERIC(5,1), shoulder_to_under_bust NUMERIC(5,1),
                front_length NUMERIC(5,1), back_length NUMERIC(5,1), front_width NUMERIC(5,1),
                back_width NUMERIC(5,1), side_seam_length NUMERIC(5,1), strap_width NUMERIC(5,1),
                princess_line_length NUMERIC(5,1), cup_size_padding VARCHAR(100),
                bust_shape_observation VARCHAR(300), high_waist_round NUMERIC(5,1),
                low_waist_round NUMERIC(5,1), seat_round NUMERIC(5,1), thigh_round NUMERIC(5,1),
                knee_round NUMERIC(5,1), calf_round NUMERIC(5,1), bottom_round NUMERIC(5,1),
                waist_to_hip_length NUMERIC(5,1), waist_to_knee NUMERIC(5,1), slit_height NUMERIC(5,1),
                can_can_requirement VARCHAR(200), waist_finish VARCHAR(100), waist_gather VARCHAR(200),
                ankle_round NUMERIC(5,1), inseam_length NUMERIC(5,1), rise_length NUMERIC(5,1),
                crotch_depth NUMERIC(5,1), collar_round NUMERIC(5,1), front_slit_length NUMERIC(5,1),
                kali_width NUMERIC(5,1), number_of_kalis VARCHAR(50), trail_length NUMERIC(5,1),
                under_bust_belt_length NUMERIC(5,1), back_opening_length NUMERIC(5,1),
                waist_joint_length NUMERIC(5,1), kali_length NUMERIC(5,1),
                mid_thigh_round NUMERIC(5,1), front_rise NUMERIC(5,1), back_rise NUMERIC(5,1),
                waistband_width NUMERIC(5,1), boutique_observations VARCHAR(500),
                notes TEXT, created_at TIMESTAMP, updated_at TIMESTAMP
            )""");
    }

    private void seedCategories(Statement stmt) throws Exception {
        stmt.execute("""
            INSERT INTO product_category (category_name, is_online) VALUES
                ('Sarees', TRUE), ('Dress Materials', TRUE), ('Blouse Pieces', TRUE),
                ('Salwar Suits', TRUE), ('Lehenga Sets', TRUE), ('Kurtis', TRUE),
                ('Fabrics', TRUE), ('Accessories', TRUE), ('Ready Made', FALSE), ('Others', FALSE)
            ON CONFLICT (category_name) DO NOTHING""");
    }

    private void createAdminUser(Connection conn, String username,
                                  String password, String fullName) throws Exception {
        String hash = passwordEncoder.encode(password);
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO app_users (username, password_hash, full_name, role, is_active, created_at) " +
            "VALUES (?, ?, ?, 'ADMIN', TRUE, NOW()) ON CONFLICT (username) DO NOTHING")) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, fullName);
            ps.executeUpdate();
        }
    }
}
