package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleProductPriceRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WholesaleProductPriceRuleRepository extends JpaRepository<WholesaleProductPriceRule, Integer> {

    List<WholesaleProductPriceRule> findByProduct_WholesaleProductIdOrderByMinQuantityAscEffectiveFromAsc(Integer productId);

    List<WholesaleProductPriceRule> findByProduct_WholesaleProductIdAndIsActiveTrue(Integer productId);

    Optional<WholesaleProductPriceRule> findByPriceRuleIdAndProduct_WholesaleProductId(Integer ruleId, Integer productId);

    /** Active rules whose quantity range and effective dates contain the given quantity and date. */
    @Query("""
        SELECT r FROM WholesaleProductPriceRule r
         WHERE r.product.wholesaleProductId = :productId
           AND r.isActive = true
           AND r.minQuantity <= :quantity
           AND (r.maxQuantity IS NULL OR r.maxQuantity >= :quantity)
           AND (r.effectiveFrom IS NULL OR r.effectiveFrom <= :date)
           AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date)
         ORDER BY r.minQuantity DESC, r.priceRuleId DESC""")
    List<WholesaleProductPriceRule> findApplicable(@Param("productId") Integer productId,
                                                   @Param("quantity") BigDecimal quantity,
                                                   @Param("date") LocalDate date);

    /** [productId, activeRuleCount] for every product that has at least one active rule. */
    @Query("SELECT r.product.wholesaleProductId, COUNT(r) FROM WholesaleProductPriceRule r WHERE r.isActive = true GROUP BY r.product.wholesaleProductId")
    List<Object[]> countActiveByProduct();
}
