package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleSalesDocument;
import com.example.mybill.wholesale.entity.WholesaleSalesDocumentStatus;
import com.example.mybill.wholesale.entity.WholesaleSalesDocumentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WholesaleSalesDocumentRepository extends JpaRepository<WholesaleSalesDocument, Integer> {

    /** like = "%term%" lower-case ("%%" = everything); from/to always set. */
    @Query("""
        SELECT d FROM WholesaleSalesDocument d
         WHERE d.docType IN :types
           AND d.status IN :statuses
           AND d.documentDate BETWEEN :fromDate AND :toDate
           AND (LOWER(d.documentNumber) LIKE :like
                OR LOWER(d.customerName) LIKE :like
                OR LOWER(COALESCE(d.customerBusinessName, '')) LIKE :like)
         ORDER BY d.documentDate DESC, d.salesDocumentId DESC""")
    List<WholesaleSalesDocument> search(@Param("types") Collection<WholesaleSalesDocumentType> types,
                                        @Param("statuses") Collection<WholesaleSalesDocumentStatus> statuses,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate,
                                        @Param("like") String like,
                                        Pageable page);

    @EntityGraph(attributePaths = {"items", "items.product", "customer", "sourceQuotation"})
    @Query("SELECT d FROM WholesaleSalesDocument d WHERE d.salesDocumentId = :id")
    Optional<WholesaleSalesDocument> findByIdWithItems(@Param("id") Integer id);

    /** Row lock so a document cannot be cancelled twice concurrently (stock would be restored twice). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM WholesaleSalesDocument d WHERE d.salesDocumentId = :id")
    Optional<WholesaleSalesDocument> findByIdForUpdate(@Param("id") Integer id);

    List<WholesaleSalesDocument> findBySourceQuotation_QuotationIdOrderByCreatedAtAsc(Integer quotationId);

    long countBySourceQuotation_QuotationIdAndStatusIn(Integer quotationId, Collection<WholesaleSalesDocumentStatus> statuses);

    boolean existsByDocumentNumber(String documentNumber);

    /** Documents that reference the given document (e.g. credit / debit notes against an invoice). */
    List<WholesaleSalesDocument> findByReferenceDocument_SalesDocumentId(Integer salesDocumentId);

    boolean existsByCustomer_WholesaleCustomerId(Integer customerId);
}
