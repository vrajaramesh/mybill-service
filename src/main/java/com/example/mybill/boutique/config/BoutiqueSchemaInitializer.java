package com.example.mybill.boutique.config;

import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the boutique agent migrations (db/boutique/V*.sql) to every firm schema.
 *
 * Same mechanism as WholesaleSchemaInitializer: runs on startup for existing firms and lazily (via
 * AgentAuthFilter → ensureSchema) for firms registered later; scripts are versioned in
 * boutique_schema_version and applied under a per-schema advisory lock, so concurrent instances are safe.
 * dev-seed.sql is deliberately NOT listed here.
 */
@Component
@Order(21)
public class BoutiqueSchemaInitializer implements ApplicationRunner {

    /** Ordered list of migration scripts. Append new versions; never edit an applied script. */
    static final List<String> SCRIPTS = List.of(
        "db/boutique/V1__boutique_catalog.sql",
        "db/boutique/V2__boutique_customers.sql",
        "db/boutique/V3__boutique_orders.sql",
        "db/boutique/V4__boutique_order_delivery_estimate.sql",
        "db/boutique/V5__boutique_order_payments.sql",
        "db/boutique/V6__marketing_attribution.sql",
        "db/boutique/V7__instagram_support.sql",
        "db/boutique/V8__size_chart_3xl_4xl.sql",
        "db/boutique/V9__exact_measurements.sql",
        "db/boutique/V10__custom_size.sql",
        "db/boutique/V11__fabric_orders.sql",
        "db/boutique/V12__design_fabric_links.sql",
        "db/boutique/V13__collections_and_size_stock.sql");

    /** Public-schema objects (idempotent, run on every startup). */
    static final String PUBLIC_SCRIPT = "db/boutique/public_razorpay_webhook_events.sql";

    private final Set<String> readySchemas = ConcurrentHashMap.newKeySet();

    @Autowired private DataSource dataSource;
    @Autowired private FirmRepository firmRepository;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(PUBLIC_SCRIPT));
        } catch (Exception e) {
            System.err.println("[BoutiqueSchema] Public schema objects failed: " + e.getMessage());
        }
        for (Firm firm : firmRepository.findAll()) {
            String schema = firm.getSchemaName();
            if (schema == null || schema.isBlank()) continue;
            try {
                migrate(schema);
            } catch (Exception e) {
                System.err.println("[BoutiqueSchema] Failed for schema " + schema + ": " + e.getMessage());
            }
        }
    }

    /** Idempotent; cheap after the first successful call per schema. */
    public void ensureSchema(String schema) {
        if (readySchemas.contains(schema)) return;
        if (!firmRepository.existsBySchemaName(schema)) {
            throw new IllegalStateException("Unknown firm schema: " + schema);
        }
        migrate(schema);
    }

    private void verifyChecksums(Connection conn, String schema) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, script, checksum FROM boutique_schema_version ORDER BY version")) {
            while (rs.next()) {
                int version = rs.getInt(1);
                if (version < 1 || version > SCRIPTS.size()) continue;
                if (!checksum(SCRIPTS.get(version - 1)).equals(rs.getString(3))) {
                    System.err.println("[BoutiqueSchema] WARNING: " + rs.getString(2) + " was changed after it was applied to schema "
                        + schema + ". The change will NOT be applied there — move it to a new migration script.");
                }
            }
        }
    }

    static String checksum(String script) {
        try (java.io.InputStream in = new ClassPathResource(script).getInputStream()) {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + script, e);
        }
    }

    /** Customers whose phone looks like a number but could not get a unique phone_e164 (shared by several rows). */
    private void reportUnlinkedPhones(Connection conn, String schema) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT COUNT(*) FROM customers
                  WHERE phone_e164 IS NULL
                    AND LENGTH(REGEXP_REPLACE(COALESCE(phone, ''), '[^0-9]', '', 'g')) >= 10""")) {
            if (rs.next() && rs.getInt(1) > 0) {
                System.out.println("[BoutiqueSchema] " + schema + ": " + rs.getInt(1)
                    + " customer(s) have a phone number that is shared with another customer or not a recognised mobile,"
                    + " so phone_e164 was left empty. Merge duplicates in the Customers screen to link them.");
            }
        }
    }

    private synchronized void migrate(String schema) {
        if (readySchemas.contains(schema)) return;
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL search_path TO \"" + schema.replace("\"", "") + "\", public");
                }
                try (PreparedStatement lock = conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                    lock.setString(1, "boutique_schema:" + schema);
                    lock.execute();
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                        CREATE TABLE IF NOT EXISTS boutique_schema_version (
                            version    INTEGER      PRIMARY KEY,
                            script     VARCHAR(200) NOT NULL,
                            checksum   VARCHAR(64)  NOT NULL,
                            applied_at TIMESTAMP    NOT NULL DEFAULT NOW()
                        )""");
                }
                verifyChecksums(conn, schema);
                int current = 0;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM boutique_schema_version")) {
                    if (rs.next()) current = rs.getInt(1);
                }
                for (int v = current + 1; v <= SCRIPTS.size(); v++) {
                    String script = SCRIPTS.get(v - 1);
                    ScriptUtils.executeSqlScript(conn, new ClassPathResource(script));
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO boutique_schema_version (version, script, checksum) VALUES (?, ?, ?)")) {
                        ins.setInt(1, v);
                        ins.setString(2, script);
                        ins.setString(3, checksum(script));
                        ins.executeUpdate();
                    }
                    System.out.println("[BoutiqueSchema] Applied " + script + " to schema " + schema);
                }
                if (current < SCRIPTS.size()) reportUnlinkedPhones(conn, schema);
                conn.commit();
                readySchemas.add(schema);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Boutique schema migration failed for " + schema + ": " + e.getMessage(), e);
        }
    }
}
