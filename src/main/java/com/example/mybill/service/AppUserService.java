package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AppUserService {

    @Autowired
    private AppUserRepository appUserRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // Admin creation is now handled per-firm by FirmService during firm registration.

    public List<AppUser> getAllUsers() {
        return appUserRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<AppUser> getUserById(Integer id) {
        return appUserRepository.findById(id);
    }

    public AppUser createUser(AppUser user) {
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        user.setPasswordHash(passwordEncoder.encode(user.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        return appUserRepository.save(user);
    }

    public AppUser updateUser(Integer id, AppUser details) {
        return appUserRepository.findById(id).map(user -> {
            user.setFullName(details.getFullName());
            user.setEmail(details.getEmail());
            user.setPhone(details.getPhone());
            user.setRole(details.getRole());
            user.setIsActive(details.getIsActive());
            // Update password only if provided
            if (details.getPassword() != null && !details.getPassword().isBlank()) {
                user.setPasswordHash(passwordEncoder.encode(details.getPassword()));
            }
            return appUserRepository.save(user);
        }).orElse(null);
    }

    public void deleteUser(Integer id) {
        appUserRepository.deleteById(id);
    }

    public Optional<AppUser> authenticate(String username, String rawPassword) {
        return appUserRepository.findByUsername(username)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive())
                      && passwordEncoder.matches(rawPassword, u.getPasswordHash()));
    }
}