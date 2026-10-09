package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleInventoryLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Append-only: only inserts and reads are used. */
public interface WholesaleInventoryLedgerRepository extends JpaRepository<WholesaleInventoryLedgerEntry, Long>,
        JpaSpecificationExecutor<WholesaleInventoryLedgerEntry> {

    List<WholesaleInventoryLedgerEntry> findByReferenceTypeAndReferenceIdOrderByLedgerIdAsc(String referenceType, Integer referenceId);

    boolean existsByReversesLedgerId(Long ledgerId);

    boolean existsByProduct_WholesaleProductId(Integer productId);

    /** Net movement (in − out) of a product before a date — the opening balance for a date range. */
    @Query("""
        SELECT COALESCE(SUM(l.quantityIn - l.quantityOut), 0) FROM WholesaleInventoryLedgerEntry l
         WHERE l.product.wholesaleProductId = :productId AND l.transactionDate < :date""")
    BigDecimal netBefore(@Param("productId") Integer productId, @Param("date") LocalDate date);

    /** [productId, netBefore(from), in(from..to), out(from..to), lastMovementDate] for every product with ledger rows. */
    @Query("""
        SELECT l.product.wholesaleProductId,
               COALESCE(SUM(CASE WHEN l.transactionDate < :fromDate THEN l.quantityIn - l.quantityOut ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN l.transactionDate BETWEEN :fromDate AND :toDate THEN l.quantityIn ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN l.transactionDate BETWEEN :fromDate AND :toDate THEN l.quantityOut ELSE 0 END), 0),
               MAX(l.transactionDate)
          FROM WholesaleInventoryLedgerEntry l
         WHERE l.transactionDate <= :toDate
         GROUP BY l.product.wholesaleProductId""")
    List<Object[]> movementSummary(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    /** [productId, Σin − Σout, last movement date] — used to check the ledger agrees with current stock. */
    @Query("""
        SELECT l.product.wholesaleProductId, SUM(l.quantityIn - l.quantityOut), MAX(l.transactionDate)
          FROM WholesaleInventoryLedgerEntry l GROUP BY l.product.wholesaleProductId""")
    List<Object[]> netByProduct();
}
