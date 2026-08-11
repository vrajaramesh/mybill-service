package com.example.mybill.multitenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider {

    @Autowired
    private DataSource dataSource;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        System.err.println("[TENANT] getConnection called with: " + tenantIdentifier);
        Connection connection = getAnyConnection();
        // SET LOCAL is transaction-scoped — required for PgBouncer in transaction pooling mode
        connection.createStatement().execute(
            "SET LOCAL search_path TO \"" + tenantIdentifier + "\", public"
        );
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        System.err.println("[TENANT] releaseConnection called with: " + tenantIdentifier);
        // No explicit reset needed — SET LOCAL is automatically rolled back when transaction ends
        releaseAnyConnection(connection);
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap as " + unwrapType.getName());
    }
}
