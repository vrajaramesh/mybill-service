package com.example.mybill.service;

import com.example.mybill.dto.*;
import com.example.mybill.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class DebitNoteService {
    @Autowired private DebitNoteRepository debitNoteRepository;
    @Autowired private DebitNoteItemRepository debitNoteItemRepository;
    @Autowired private PurchaseRepository purchaseRepository;
    @Autowired private ProductRepository productRepository;

    public List<DebitNote> getByPurchase(Integer purchaseId) {
        return debitNoteRepository.findByPurchase_PurchaseIdOrderByCreatedAtDesc(purchaseId);
    }

    @Transactional
    public DebitNote create(Integer purchaseId, DebitNote note) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
            .orElseThrow(() -> new IllegalArgumentException("Purchase not found"));
        if (note.getItems() == null || note.getItems().isEmpty())
            throw new IllegalArgumentException("At least one return item is required");

        Map<Integer, PurchaseItem> purchaseItems = new HashMap<>();
        for (PurchaseItem item : purchase.getPurchaseItems()) purchaseItems.put(item.getPurchaseItemId(), item);

        note.setPurchase(purchase);
        note.setNoteNumber("DN-" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
        note.setNoteDate(note.getNoteDate() != null ? note.getNoteDate() : LocalDate.now());
        note.setCreatedAt(LocalDateTime.now());
        BigDecimal baseTotal = BigDecimal.ZERO;
        BigDecimal gstTotal = BigDecimal.ZERO;

        for (DebitNoteItem requested : note.getItems()) {
            Integer purchaseItemId = requested.getPurchaseItem() != null
                ? requested.getPurchaseItem().getPurchaseItemId() : null;
            PurchaseItem original = purchaseItems.get(purchaseItemId);
            if (original == null) throw new IllegalArgumentException("Return item is not part of this purchase");
            BigDecimal quantity = requested.getQuantity();
            if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("Return quantity must be positive");
            BigDecimal alreadyReturned = Optional.ofNullable(debitNoteItemRepository.sumQuantityByPurchaseItem_PurchaseItemId(purchaseItemId)).orElse(BigDecimal.ZERO);
            BigDecimal remaining = original.getQuantity().subtract(alreadyReturned);
            if (quantity.compareTo(remaining) > 0)
                throw new IllegalArgumentException("Return quantity exceeds remaining quantity for " + original.getProduct().getProductName());

            BigDecimal unitPrice = original.getUnitPrice();
            BigDecimal gst = original.getGst() != null ? original.getGst() : new BigDecimal("5");
            BigDecimal base = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal gstAmount = base.multiply(gst).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            requested.setDebitNote(note);
            requested.setPurchaseItem(original);
            requested.setProduct(original.getProduct());
            requested.setUnitPrice(unitPrice);
            requested.setGst(gst);
            requested.setTotalAmount(base);
            requested.setFinalAmount(base.add(gstAmount));
            baseTotal = baseTotal.add(base);
            gstTotal = gstTotal.add(gstAmount);

            productRepository.findById(original.getProduct().getProductId()).ifPresent(product -> {
                BigDecimal stock = product.getStockQuantity() != null ? product.getStockQuantity() : BigDecimal.ZERO;
                product.setStockQuantity(stock.subtract(quantity).max(BigDecimal.ZERO));
                productRepository.save(product);
            });
        }
        note.setTotalAmount(baseTotal.setScale(2, RoundingMode.HALF_UP));
        note.setGst(gstTotal.setScale(2, RoundingMode.HALF_UP));
        note.setFinalAmount(baseTotal.add(gstTotal).setScale(2, RoundingMode.HALF_UP));
        return debitNoteRepository.save(note);
    }
}