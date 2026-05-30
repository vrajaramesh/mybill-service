package com.example.mybill.multitenancy;

public class TenantContext {

    private static final ThreadLocal<String> currentTenant = new InheritableThreadLocal<>();

    public static void setCurrentTenant(String schema) {
        currentTenant.set(schema);
    }

    public static String getCurrentTenant() {
        return currentTenant.get();
    }

    public static void clear() {
        currentTenant.remove();
    }
}
