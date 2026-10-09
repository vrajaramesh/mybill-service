package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleQuotation;
import com.example.mybill.wholesale.entity.WholesaleQuotationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WholesaleQuotationRepository extends JpaRepository<WholesaleQuotation, Integer> {

    /** like = "%term%" lower-case ("%%" = everything); from/to always set (use wide defaults for "no filter"). */
    @Query("""
        SELECT q FROM WholesaleQuotation q
         WHERE q.status IN :statuses
           AND q.quotationDate BETWEEN :fromDate AND :toDate
           AND (LOWER(COALESCE(q.quotationNumber, '')) LIKE :like
                OR LOWER(q.customerName) LIKE :like
                OR LOWER(COALESCE(q.customerBusinessName, '')) LIKE :like)
         ORDER BY q.quotationDate DESC, q.quotationId DESC""")
    List<WholesaleQuotation> search(@Param("statuses") Collection<WholesaleQuotationStatus> statuses,
                                    @Param("fromDate") LocalDate fromDate,
                                    @Param("toDate") LocalDate toDate,
                                    @Param("like") String like,
                                    Pageable page);

    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT q FROM WholesaleQuotation q WHERE q.quotationId = :id")
    Optional<WholesaleQuotation> findByIdWithItems(@Param("id") Integer id);

    /** Row lock that serialises conversions of one quotation (prevents double conversion on double-click). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM WholesaleQuotation q WHERE q.quotationId = :id")
    Optional<WholesaleQuotation> findByIdForUpdate(@Param("id") Integer id);

    boolean existsByQuotationNumber(String quotationNumber);

    boolean existsByCustomer_WholesaleCustomerId(Integer customerId);

    /** ISSUED quotations past their validity date become EXPIRED. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE WholesaleQuotation q
           SET q.status = com.example.mybill.wholesale.entity.WholesaleQuotationStatus.EXPIRED,
               q.statusChangedAt = :now, q.statusChangedBy = 'SYSTEM', q.updatedAt = :now
         WHERE q.status = com.example.mybill.wholesale.entity.WholesaleQuotationStatus.ISSUED
           AND q.validUntil < :today""")
    int expireOverdue(@Param("today") LocalDate today, @Param("now") LocalDateTime now);
}
