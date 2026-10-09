package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface WholesaleProductRepository extends JpaRepository<WholesaleProduct, Integer> {

    @EntityGraph(attributePaths = {"supplier", "images"})
    List<WholesaleProduct> findAllByOrderByProductNameAsc();

    @EntityGraph(attributePaths = {"supplier", "images"})
    List<WholesaleProduct> findByIsActiveTrueOrderByProductNameAsc();

    boolean existsByProductCodeIgnoreCase(String productCode);

    boolean existsByProductCodeIgnoreCaseAndWholesaleProductIdNot(String productCode, Integer wholesaleProductId);

    /** Row lock used to serialise price-rule changes per product (prevents concurrent overlapping rules). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM WholesaleProduct p WHERE p.wholesaleProductId = :id")
    Optional<WholesaleProduct> findByIdForUpdate(@Param("id") Integer id);

    @Query("SELECT COUNT(i) > 0 FROM WholesalePurchaseItem i WHERE i.product.wholesaleProductId = :id")
    boolean hasPurchases(@Param("id") Integer id);

    @Query("SELECT COUNT(i) > 0 FROM WholesaleQuotationItem i WHERE i.product.wholesaleProductId = :id")
    boolean hasQuotationItems(@Param("id") Integer id);

    @Query("SELECT COUNT(i) > 0 FROM WholesaleSalesDocumentItem i WHERE i.product.wholesaleProductId = :id")
    boolean hasSalesDocumentItems(@Param("id") Integer id);


    /**
     * Records the latest purchase rate and supplier. Stock quantities are NOT changed here — every stock change goes
     * through WholesaleInventoryService so it is written to the inventory ledger.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE wholesale_products
           SET purchase_rate = :rate,
               supplier_id   = :supplierId,
               updated_at    = NOW()
         WHERE wholesale_product_id = :id""", nativeQuery = true)
    int updateLastPurchase(@Param("id") Integer id, @Param("rate") BigDecimal rate, @Param("supplierId") Integer supplierId);
}
