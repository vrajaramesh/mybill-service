package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.BoutiqueCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BoutiqueCustomerRepository extends JpaRepository<BoutiqueCustomer, Integer> {

    Optional<BoutiqueCustomer> findByPhoneE164(String phoneE164);

    Optional<BoutiqueCustomer> findByInstagramUserId(String instagramUserId);

    /**
     * Shop customers whose legacy free-text phone is this national number but who have no phone_e164 yet
     * (e.g. duplicates the V2 backfill skipped). Newest first.
     */
    @Query(value = """
        SELECT * FROM customers
         WHERE phone_e164 IS NULL
           AND RIGHT(REGEXP_REPLACE(COALESCE(phone, ''), '[^0-9]', '', 'g'), 10) = :national
         ORDER BY created_at DESC NULLS LAST, customer_id DESC""", nativeQuery = true)
    List<BoutiqueCustomer> findUnlinkedByNationalPhone(@Param("national") String national);
}
