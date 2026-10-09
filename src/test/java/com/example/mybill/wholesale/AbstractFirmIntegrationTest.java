package com.example.mybill.wholesale;

import com.example.mybill.dto.Firm;
import com.example.mybill.multitenancy.JwtUtil;
import com.example.mybill.service.FirmService;
import com.example.mybill.service.MybillServiceApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Boots the full application against a throwaway PostgreSQL (with pgvector, as in production).
 * One container is shared by all test classes so the Spring context is cached; each test class
 * registers its own firm (= its own schema) through the real FirmService provisioning.
 */
@SpringBootTest(classes = MybillServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractFirmIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            // FirmService provisions with search_path = <firm schema> only, so the pgvector type must be
            // resolvable from any schema. Installing it in pg_catalog mirrors a database where that works.
            try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("CREATE EXTENSION IF NOT EXISTS vector SCHEMA pg_catalog");
            } catch (Exception e) {
                throw new IllegalStateException("Could not prepare test database", e);
            }
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired private FirmService firmService;
    @Autowired private JwtUtil jwtUtil;

    protected record FirmCtx(String code, String schema, String bearer) {}

    protected FirmCtx registerFirm(String code) {
        Integer superadminId = jdbc.queryForObject(
            "SELECT user_id FROM public.app_users_public WHERE role = 'SUPERADMIN' ORDER BY user_id LIMIT 1", Integer.class);
        Firm firm = firmService.registerFirm("Firm " + code, code, code + "@example.com",
            "admin_" + code, "Passw0rd!", "Admin " + code, superadminId);
        String token = jwtUtil.generateToken("admin_" + code, firm.getSchemaName(), firm.getFirmId(), "ADMIN");
        return new FirmCtx(firm.getFirmCode(), firm.getSchemaName(), "Bearer " + token);
    }

    protected String superadminBearer() {
        return "Bearer " + jwtUtil.generateToken("superadmin", "public", 0L, "SUPERADMIN");
    }

    protected int insertSupplier(FirmCtx f, String name, String gstin) {
        return jdbc.queryForObject("INSERT INTO \"" + f.schema() + "\".suppliers (supplier_name, gst_number, created_at) "
            + "VALUES (?, ?, NOW()) RETURNING supplier_id", Integer.class, name, gstin);
    }

    /** Inserted directly (not via /api/products) so Hermes content generation is not triggered. */
    protected void insertRetailProduct(FirmCtx f, int id, String name, String stock) {
        jdbc.update("INSERT INTO \"" + f.schema() + "\".products (product_id, product_name, unit, cost_price, selling_price, "
            + "stock_quantity, min_stock_level, is_active, is_online, created_at, updated_at) "
            + "VALUES (?, ?, 'Meters', 80, 150, ?, 0, TRUE, TRUE, NOW(), NOW())", id, name, new BigDecimal(stock));
    }

    protected BigDecimal retailStock(FirmCtx f, int productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM \"" + f.schema() + "\".products WHERE product_id = ?",
            BigDecimal.class, productId);
    }

    protected BigDecimal wholesaleStock(FirmCtx f, int wholesaleProductId) {
        return jdbc.queryForObject("SELECT available_quantity FROM \"" + f.schema()
            + "\".wholesale_products WHERE wholesale_product_id = ?", BigDecimal.class, wholesaleProductId);
    }

    protected long count(FirmCtx f, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + f.schema() + "\"." + table, Long.class);
    }

    protected boolean tableExists(FirmCtx f, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
            Boolean.class, f.schema(), table));
    }

    /** Fingerprint of everything retail: product rows (incl. stock and price) and retail document counts. */
    protected String retailSnapshot(FirmCtx f) {
        String s = "\"" + f.schema() + "\".";
        return jdbc.queryForObject(
            "SELECT COALESCE((SELECT string_agg(product_id || ':' || product_name || ':' || stock_quantity || ':' || selling_price, '|' "
                + "ORDER BY product_id) FROM " + s + "products), '') "
                + "|| '#purchases=' || (SELECT COUNT(*) FROM " + s + "purchases) "
                + "|| '#purchase_items=' || (SELECT COUNT(*) FROM " + s + "purchase_items) "
                + "|| '#purchase_payments=' || (SELECT COUNT(*) FROM " + s + "purchase_payments) "
                + "|| '#bills=' || (SELECT COUNT(*) FROM " + s + "bills) "
                + "|| '#bill_items=' || (SELECT COUNT(*) FROM " + s + "bill_items) "
                + "|| '#product_images=' || (SELECT COUNT(*) FROM " + s + "product_images)",
            String.class);
    }
}
