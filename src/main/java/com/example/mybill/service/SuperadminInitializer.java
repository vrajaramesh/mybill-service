package com.example.mybill.service;

import com.example.mybill.dto.AppUserPublic;
import com.example.mybill.repository.AppUserPublicRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Seeds a default superadmin user on first startup if none exists.
 * Default credentials: username=superadmin / password=MyBill@SA123
 * Change the password immediately after first login in production.
 */
@Component
public class SuperadminInitializer implements ApplicationRunner {

    @Autowired
    private AppUserPublicRepository appUserPublicRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void run(ApplicationArguments args) {
        if (appUserPublicRepository.count() == 0) {
            AppUserPublic superadmin = new AppUserPublic();
            superadmin.setUsername("superadmin");
            superadmin.setPasswordHash(passwordEncoder.encode("MyBill@SA123"));
            superadmin.setFullName("Super Administrator");
            superadmin.setEmail("superadmin@mybill.app");
            superadmin.setRole("SUPERADMIN");
            superadmin.setIsActive(true);
            superadmin.setCreatedAt(LocalDateTime.now());
            appUserPublicRepository.save(superadmin);
            System.out.println("[MyBill] Default superadmin created. username=superadmin password=MyBill@SA123");
            System.out.println("[MyBill] IMPORTANT: Change this password immediately after first login.");
        }
    }
}
