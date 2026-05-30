package com.example.mybill.multitenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver {

    @Override
    public String resolveCurrentTenantIdentifier() {
        String schema = TenantContext.getCurrentTenant();
        String resolved = (schema != null) ? schema : "public";
        System.err.println("[TENANT] resolveCurrentTenantIdentifier: " + resolved);
        return resolved;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
