package com.example.mybill.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.example.mybill")
@EnableJpaRepositories(
    basePackages = {"com.example.mybill.service", "com.example.mybill.repository"},
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager"
)
@EntityScan(basePackages = {"com.example.mybill.service", "com.example.mybill.dto"})
public class MybillServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MybillServiceApplication.class, args);
    }
}
