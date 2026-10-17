package com.example.mybill.boutique.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the boutique repositories (the application class only scans the retail packages).
 * Entities are covered by HibernateMultiTenancyConfig (packagesToScan = com.example.mybill).
 */
@Configuration
@EnableJpaRepositories(
    basePackages = "com.example.mybill.boutique.repository",
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager"
)
public class BoutiqueJpaConfig {
}
