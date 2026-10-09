package com.example.mybill.wholesale.config;

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
 * Creates the wholesale_* tables in every firm schema.
 *
 * Runs on startup for existing firms, and lazily (via WholesaleSchemaInterceptor) for firms
 * registered after startup, so FirmService does not need to change. Scripts are versioned in
 * wholesale_schema_version and applied under a per-schema advisory lock, so concurrent pods are safe.
 * Only ever creates wholesale_* tables — retail tables are never altered.
 */
@Component
@Order(20)
public class WholesaleSchemaInitializer implements ApplicationRunner {

    /** Ordered list of migration scripts. Append new versions; never edit an applied script. */
    static final List<String> SCRIPTS = List.of(
        "db/wholesale/V1__wholesale_purchases.sql",
        "db/wholesale/V2__wholesale_product_pricing.sql",
        "db/wholesale/V3__wholesale_customers.sql",
        "db/wholesale/V4__wholesale_customer_types.sql",
        "db/wholesale/V5__wholesale_business_profile.sql",
        "db/wholesale/V6__wholesale_quotations.sql",
        "db/wholesale/V7__wholesale_quotation_conversion.sql",
        "db/wholesale/V8__wholesale_sales_receipts.sql",
        "db/wholesale/V9__wholesale_credit_debit_notes.sql",
        "db/wholesale/V10__wholesale_inventory_ledger.sql",
        "db/wholesale/V11__wholesale_instagram_messaging.sql",
        "db/wholesale/V12__wholesale_ai_assistant.sql",
        "db/wholesale/V13__wholesale_purchase_cancellation_repair.sql");

    private final Set<String> readySchemas = ConcurrentHashMap.newKeySet();

    @Autowired private DataSource dataSource;
    @Autowired private FirmRepository firmRepository;

    @Override
    public void run(ApplicationArguments args) {
        for (Firm firm : firmRepository.findAll()) {
            String schema = firm.getSchemaName();
            if (schema == null || schema.isBlank()) continue;
            try {
                migrate(schema);
            } catch (Exception e) {
                System.err.println("[WholesaleSchema] Failed for schema " + schema + ": " + e.getMessage());
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

    /**
     * Applied scripts must never change: a change is not re-run, so databases that already applied the old version
     * silently miss it (V10 → repaired by V13). Rows without a checksum (applied before checksums existed) get the
     * current one recorded; afterwards any difference is logged loudly — add a new Vn script instead of editing.
     */
    private void verifyChecksums(Connection conn, String schema) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, script, checksum FROM wholesale_schema_version ORDER BY version")) {
            List<Object[]> missing = new java.util.ArrayList<>();
            while (rs.next()) {
                int version = rs.getInt(1);
                String script = rs.getString(2);
                String stored = rs.getString(3);
                if (version < 1 || version > SCRIPTS.size()) continue;
                String current = checksum(SCRIPTS.get(version - 1));
                if (stored == null) {
                    missing.add(new Object[]{version, current});
                } else if (!stored.equals(current)) {
                    System.err.println("[WholesaleSchema] WARNING: " + script + " was changed after it was applied to schema "
                        + schema + ". The change will NOT be applied there — move it to a new migration script.");
                }
            }
            try (PreparedStatement up = conn.prepareStatement("UPDATE wholesale_schema_version SET checksum = ? WHERE version = ?")) {
                for (Object[] m : missing) {
                    up.setString(1, (String) m[1]);
                    up.setInt(2, (Integer) m[0]);
                    up.executeUpdate();
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
                    lock.setString(1, "wholesale_schema:" + schema);
                    lock.execute();
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                        CREATE TABLE IF NOT EXISTS wholesale_schema_version (
                            version    INTEGER      PRIMARY KEY,
                            script     VARCHAR(200) NOT NULL,
                            applied_at TIMESTAMP    NOT NULL DEFAULT NOW()
                        )""");
                    st.execute("ALTER TABLE wholesale_schema_version ADD COLUMN IF NOT EXISTS checksum VARCHAR(64)");
                }
                verifyChecksums(conn, schema);
                int current = 0;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM wholesale_schema_version")) {
                    if (rs.next()) current = rs.getInt(1);
                }
                for (int v = current + 1; v <= SCRIPTS.size(); v++) {
                    String script = SCRIPTS.get(v - 1);
                    ScriptUtils.executeSqlScript(conn, new ClassPathResource(script));
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO wholesale_schema_version (version, script, checksum) VALUES (?, ?, ?)")) {
                        ins.setInt(1, v);
                        ins.setString(2, script);
                        ins.setString(3, checksum(script));
                        ins.executeUpdate();
                    }
                    System.out.println("[WholesaleSchema] Applied " + script + " to schema " + schema);
                }
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
            throw new IllegalStateException("Wholesale schema migration failed for " + schema + ": " + e.getMessage(), e);
        }
    }
}
