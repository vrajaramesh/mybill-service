package com.example.mybill.wholesale.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The application class only scans the retail repository packages, so wholesale repositories are
 * registered here instead of editing MybillServiceApplication. Entities are already covered by
 * HibernateMultiTenancyConfig (packagesToScan = com.example.mybill).
 */
@Configuration
@EnableJpaRepositories(
    basePackages = "com.example.mybill.wholesale.repository",
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager"
)
public class WholesaleJpaConfig {
}
