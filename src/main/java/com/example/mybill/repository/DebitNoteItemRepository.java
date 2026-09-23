package com.example.mybill.repository;

import com.example.mybill.dto.DebitNoteItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;

public interface DebitNoteItemRepository extends JpaRepository<DebitNoteItem, Integer> {
    @Query("select coalesce(sum(i.quantity), 0) from DebitNoteItem i where i.purchaseItem.purchaseItemId = :purchaseItemId")
    BigDecimal sumQuantityByPurchaseItem_PurchaseItemId(Integer purchaseItemId);
}