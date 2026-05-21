# MyBill Backend - Firm Registration Implementation

This guide provides complete Java/Spring Boot code for implementing the firm registration feature.

## 1. Database Schema

```sql
-- Create firms table
CREATE TABLE IF NOT EXISTS public.firms (
    firm_id SERIAL PRIMARY KEY,
    firm_name VARCHAR(255) UNIQUE NOT NULL,
    firm_code VARCHAR(100) UNIQUE NOT NULL,
    schema_name VARCHAR(100) UNIQUE NOT NULL,
    owner_email VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Create index on firm_code for faster lookups
CREATE INDEX idx_firms_firm_code ON public.firms(firm_code);

-- Add firm_id to users table if not already present
ALTER TABLE users ADD COLUMN IF NOT EXISTS firm_id INT REFERENCES firms(firm_id);

-- Create index on users.firm_id
CREATE INDEX idx_users_firm_id ON users(firm_id);
```

## 2. JPA Entities

### File: `src/main/java/com/mybill/entity/Firm.java`

```java
package com.mybill.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "firms")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Firm {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "firm_id")
    private Long firmId;
    
    @Column(name = "firm_name", nullable = false, unique = true)
    private String firmName;
    
    @Column(name = "firm_code", nullable = false, unique = true)
    private String firmCode;
    
    @Column(name = "schema_name", nullable = false, unique = true)
    private String schemaName;
    
    @Column(name = "owner_email", nullable = false)
    private String ownerEmail;
    
    @Column(name = "is_active")
    private Boolean isActive = true;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

### Update: `User.java` (if not already present)

```java
// Add this field to the User entity
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "firm_id")
private Firm firm;
```

## 3. DTOs

### File: `src/main/java/com/mybill/dto/FirmRegistrationRequest.java`

```java
package com.mybill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FirmRegistrationRequest {
    
    @NotBlank(message = "Firm name is required")
    @Size(min = 2, max = 255, message = "Firm name must be between 2 and 255 characters")
    private String firmName;
    
    @NotBlank(message = "Firm code is required")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Firm code can only contain letters, numbers, underscores, and hyphens")
    @Size(min = 3, max = 100, message = "Firm code must be between 3 and 100 characters")
    private String firmCode;
    
    @NotBlank(message = "Owner email is required")
    @Email(message = "Invalid email format")
    private String ownerEmail;
    
    @NotBlank(message = "Admin username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    private String adminUsername;
    
    @NotBlank(message = "Admin password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String adminPassword;
    
    @NotBlank(message = "Admin full name is required")
    @Size(min = 2, max = 255, message = "Full name must be between 2 and 255 characters")
    private String adminFullName;
}
```

### File: `src/main/java/com/mybill/dto/FirmResponse.java`

```java
package com.mybill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FirmResponse {
    private Long firmId;
    private String firmName;
    private String firmCode;
    private String message;
}
```

### File: `src/main/java/com/mybill/dto/FirmDTO.java`

```java
package com.mybill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FirmDTO {
    private Long firmId;
    private String firmName;
    private String firmCode;
    private String schemaName;
    private String ownerEmail;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
```

## 4. Repository

### File: `src/main/java/com/mybill/repository/FirmRepository.java`

```java
package com.mybill.repository;

import com.mybill.entity.Firm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FirmRepository extends JpaRepository<Firm, Long> {
    Optional<Firm> findByFirmCode(String firmCode);
    Optional<Firm> findByFirmName(String firmName);
    Optional<Firm> findBySchemaName(String schemaName);
    boolean existsByFirmCode(String firmCode);
    boolean existsByFirmName(String firmName);
}
```

## 5. Service Layer

### File: `src/main/java/com/mybill/service/FirmService.java`

```java
package com.mybill.service;

import com.mybill.dto.FirmDTO;
import com.mybill.dto.FirmRegistrationRequest;
import com.mybill.dto.FirmResponse;
import com.mybill.entity.Firm;
import com.mybill.entity.User;
import com.mybill.repository.FirmRepository;
import com.mybill.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FirmService {
    
    private final FirmRepository firmRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    
    /**
     * Register a new firm with tenant schema and admin user
     */
    @Transactional
    public FirmResponse registerFirm(FirmRegistrationRequest request) {
        log.info("Registering new firm: {}", request.getFirmName());
        
        // Validate uniqueness
        if (firmRepository.existsByFirmCode(request.getFirmCode())) {
            throw new IllegalArgumentException("Firm code already exists: " + request.getFirmCode());
        }
        
        if (firmRepository.existsByFirmName(request.getFirmName())) {
            throw new IllegalArgumentException("Firm name already exists: " + request.getFirmName());
        }
        
        // Create firm entity
        String schemaName = generateSchemaName(request.getFirmCode());
        Firm firm = Firm.builder()
                .firmName(request.getFirmName())
                .firmCode(request.getFirmCode().toLowerCase())
                .schemaName(schemaName)
                .ownerEmail(request.getOwnerEmail())
                .isActive(true)
                .build();
        
        firm = firmRepository.save(firm);
        log.info("Firm entity created with ID: {}", firm.getFirmId());
        
        try {
            // Create tenant schema
            createTenantSchema(schemaName);
            
            // Create admin user
            createAdminUser(firm, request.getAdminUsername(), request.getAdminPassword(), 
                          request.getAdminFullName());
            
            log.info("Firm registration completed successfully: {}", firm.getFirmId());
            
            return FirmResponse.builder()
                    .firmId(firm.getFirmId())
                    .firmName(firm.getFirmName())
                    .firmCode(firm.getFirmCode())
                    .message("Firm registered successfully")
                    .build();
        } catch (Exception e) {
            log.error("Error during firm registration", e);
            // Rollback - delete the firm
            firmRepository.delete(firm);
            throw new RuntimeException("Failed to register firm: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create PostgreSQL schema for tenant
     */
    private void createTenantSchema(String schemaName) {
        try {
            String createSchemaSQL = String.format("CREATE SCHEMA IF NOT EXISTS %s", schemaName);
            jdbcTemplate.execute(createSchemaSQL);
            log.info("Schema created: {}", schemaName);
            
            // Initialize schema with required tables (if needed)
            // You can add migration scripts here
        } catch (Exception e) {
            log.error("Failed to create schema: {}", schemaName, e);
            throw new RuntimeException("Failed to create tenant schema", e);
        }
    }
    
    /**
     * Create admin user for the firm
     */
    @Transactional
    private void createAdminUser(Firm firm, String username, String password, String fullName) {
        // Check if user already exists
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        
        User admin = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .fullName(fullName)
                .role("ADMIN")  // or use UserRole enum
                .isActive(true)
                .firm(firm)
                .build();
        
        userRepository.save(admin);
        log.info("Admin user created for firm: {}", firm.getFirmId());
    }
    
    /**
     * Generate schema name from firm code
     */
    private String generateSchemaName(String firmCode) {
        return "firm_" + firmCode.toLowerCase().replace("-", "_");
    }
    
    /**
     * Get all firms
     */
    public List<FirmDTO> getAllFirms() {
        return firmRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Get firm by code
     */
    public FirmDTO getFirmByCode(String firmCode) {
        return firmRepository.findByFirmCode(firmCode)
                .map(this::convertToDTO)
                .orElseThrow(() -> new IllegalArgumentException("Firm not found: " + firmCode));
    }
    
    /**
     * Convert Firm entity to DTO
     */
    private FirmDTO convertToDTO(Firm firm) {
        return FirmDTO.builder()
                .firmId(firm.getFirmId())
                .firmName(firm.getFirmName())
                .firmCode(firm.getFirmCode())
                .schemaName(firm.getSchemaName())
                .ownerEmail(firm.getOwnerEmail())
                .isActive(firm.getIsActive())
                .createdAt(firm.getCreatedAt())
                .build();
    }
}
```

## 6. Controller

### File: `src/main/java/com/mybill/controller/FirmController.java`

```java
package com.mybill.controller;

import com.mybill.dto.FirmDTO;
import com.mybill.dto.FirmRegistrationRequest;
import com.mybill.dto.FirmResponse;
import com.mybill.service.FirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/firms")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "${app.cors.allowed-origins:*}")
public class FirmController {
    
    private final FirmService firmService;
    
    /**
     * Register a new firm
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerFirm(
            @Valid @RequestBody FirmRegistrationRequest request,
            BindingResult bindingResult) {
        
        log.info("Firm registration request for: {}", request.getFirmName());
        
        // Check for validation errors
        if (bindingResult.hasErrors()) {
            Map<String, String> errors = new HashMap<>();
            bindingResult.getFieldErrors().forEach(error ->
                    errors.put(error.getField(), error.getDefaultMessage())
            );
            return ResponseEntity.badRequest().body(errors);
        }
        
        try {
            FirmResponse response = firmService.registerFirm(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error during firm registration: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        } catch (Exception e) {
            log.error("Error during firm registration", e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to register firm");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
    /**
     * Get all firms (Admin only)
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<FirmDTO>> getAllFirms() {
        log.info("Fetching all firms");
        List<FirmDTO> firms = firmService.getAllFirms();
        return ResponseEntity.ok(firms);
    }
    
    /**
     * Get firm by code
     */
    @GetMapping("/{firmCode}")
    public ResponseEntity<?> getFirmByCode(@PathVariable String firmCode) {
        try {
            FirmDTO firm = firmService.getFirmByCode(firmCode);
            return ResponseEntity.ok(firm);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
```

## 7. User Repository Update

### File: `src/main/java/com/mybill/repository/UserRepository.java`

Ensure your UserRepository has:

```java
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    // ... existing methods
}
```

## 8. Application Properties (application.properties)

Add this configuration:

```properties
# Firm Registration
mybill.firm.schema-prefix=firm_

# Ensure CORS is properly configured
app.cors.allowed-origins=http://localhost:4200,http://localhost:3000,*
```

## 9. Testing the API

### Register a Firm
```bash
curl -X POST http://localhost:8080/api/firms/register \
  -H "Content-Type: application/json" \
  -d '{
    "firmName": "SRISA Fabrics",
    "firmCode": "srisa_fabrics",
    "ownerEmail": "owner@srisa.com",
    "adminUsername": "admin_srisa",
    "adminPassword": "AdminPassword@123",
    "adminFullName": "Admin User"
  }'
```

### Get All Firms
```bash
curl -X GET http://localhost:8080/api/firms \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Get Firm by Code
```bash
curl -X GET http://localhost:8080/api/firms/srisa_fabrics
```

## 10. Error Handling

The implementation includes proper error handling for:
- Duplicate firm codes/names
- Invalid input data
- Database errors
- Schema creation failures

## 11. Security Considerations

✅ **Implemented:**
- Input validation with Jakarta Bean Validation
- Password encryption with PasswordEncoder
- CORS configuration
- Transaction management for data consistency

⚠️ **Recommended:**
- Rate limiting on registration endpoint
- Email verification workflow
- Audit logging for registration events
- Password complexity validation

## 12. Migration and Deployment

Run the database migration:
```sql
-- Run the schema from Section 1
```

Update your pom.xml to ensure all dependencies are present:
```xml
<dependency>
    <groupId>jakarta.validation</groupId>
    <artifactId>jakarta.validation-api</artifactId>
</dependency>

<dependency>
    <groupId>org.hibernate.validator</groupId>
    <artifactId>hibernate-validator</artifactId>
</dependency>
```

## 13. Next Steps

1. ✅ Create Firm entity and repository
2. ✅ Implement FirmService with tenant schema creation
3. ✅ Create FirmController with registration endpoint
4. Run database migration for firms table
5. Deploy and test end-to-end
6. Add email verification (optional)
7. Add firm approval workflow (optional)

