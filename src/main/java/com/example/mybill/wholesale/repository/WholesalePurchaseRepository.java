package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesalePurchase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WholesalePurchaseRepository extends JpaRepository<WholesalePurchase, Integer> {

    @EntityGraph(attributePaths = {"supplier", "items", "items.product"})
    @Query("SELECT p FROM WholesalePurchase p ORDER BY p.invoiceDate DESC, p.wholesalePurchaseId DESC")
    List<WholesalePurchase> findAllWithItems();

    @EntityGraph(attributePaths = {"supplier", "items", "items.product"})
    @Query("SELECT p FROM WholesalePurchase p WHERE p.wholesalePurchaseId = :id")
    Optional<WholesalePurchase> findByIdWithItems(@Param("id") Integer id);

    boolean existsBySupplier_SupplierIdAndInvoiceNumberIgnoreCaseAndStatus(Integer supplierId, String invoiceNumber, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM WholesalePurchase p WHERE p.wholesalePurchaseId = :id")
    Optional<WholesalePurchase> findByIdForUpdate(@Param("id") Integer id);
}
