package com.example.mybill.boutique.payment;

import com.example.mybill.multitenancy.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * Runs JPA work for a firm schema chosen INSIDE a request (webhooks, internal jobs).
 *
 * Hibernate fixes a session's tenant when the session opens, and Spring's open-session-in-view opens one at the
 * start of every request - before a webhook has read its body and learned the firm. This temporarily unbinds
 * that request session so the work gets a fresh session for `schema`, then restores everything.
 */
@Component
public class TenantWork {

    @Autowired private EntityManagerFactory entityManagerFactory;

    public <T> T in(String schema, Supplier<T> work) {
        Object requestSession = TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schema);
        try {
            return work.get();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setCurrentTenant(previous);
            if (requestSession != null) TransactionSynchronizationManager.bindResource(entityManagerFactory, requestSession);
        }
    }
}
