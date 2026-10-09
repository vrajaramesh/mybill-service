package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleCustomer;
import com.example.mybill.wholesale.entity.WholesaleCustomerType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WholesaleCustomerRepository extends JpaRepository<WholesaleCustomer, Integer> {

    /**
     * Search used by the customer list and the document customer picker.
     * statuses = [true] for active only or [true, false] for all; types = the allowed customer types;
     * like = "%term%" (lower-case, "%%" matches everything), likeUpper = upper-case variant for GSTIN.
     */
    @Query("""
        SELECT c FROM WholesaleCustomer c
         WHERE c.isActive IN :statuses
           AND c.customerType IN :types
           AND (LOWER(c.customerName) LIKE :like
                OR LOWER(COALESCE(c.businessName, '')) LIKE :like
                OR COALESCE(c.gstNumber, '') LIKE :likeUpper
                OR COALESCE(c.phone, '') LIKE :like
                OR LOWER(COALESCE(c.city, '')) LIKE :like)
         ORDER BY LOWER(c.customerName), c.wholesaleCustomerId""")
    List<WholesaleCustomer> search(@Param("statuses") Collection<Boolean> statuses,
                                   @Param("types") Collection<WholesaleCustomerType> types,
                                   @Param("like") String like,
                                   @Param("likeUpper") String likeUpper,
                                   Pageable page);

    Optional<WholesaleCustomer> findByGstNumber(String gstNumber);

    List<WholesaleCustomer> findByPhoneEndingWith(String lastDigits);

    List<WholesaleCustomer> findByCustomerNameIgnoreCase(String customerName);
}
