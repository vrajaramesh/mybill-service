package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PurchaseItemService {

    @Autowired
    private PurchaseItemRepository purchaseItemRepository;

    public List<PurchaseItem> getAllPurchaseItems() {
        return purchaseItemRepository.findAll();
    }

    public Optional<PurchaseItem> getPurchaseItemById(Integer id) {
        return purchaseItemRepository.findById(id);
    }

    public List<PurchaseItem> getPurchaseItemsByPurchase(Integer purchaseId) {
        return purchaseItemRepository.findByPurchase_PurchaseId(purchaseId);
    }

    public List<PurchaseItem> getPurchaseItemsByProduct(Integer productId) {
        return purchaseItemRepository.findByProduct_ProductId(productId);
    }

    public PurchaseItem createPurchaseItem(PurchaseItem purchaseItem) {
        // Calculate total price if not set
        if (purchaseItem.getTotalPrice() == null) {
            purchaseItem.setTotalPrice(
                purchaseItem.getQuantity().multiply(purchaseItem.getUnitPrice())
            );
        }
        return purchaseItemRepository.save(purchaseItem);
    }

    public PurchaseItem updatePurchaseItem(Integer id, PurchaseItem purchaseItemDetails) {
        Optional<PurchaseItem> optionalPurchaseItem = purchaseItemRepository.findById(id);
        if (optionalPurchaseItem.isPresent()) {
            PurchaseItem purchaseItem = optionalPurchaseItem.get();
            purchaseItem.setQuantity(purchaseItemDetails.getQuantity());
            purchaseItem.setUnitPrice(purchaseItemDetails.getUnitPrice());

            // Recalculate total price
            purchaseItem.setTotalPrice(
                purchaseItem.getQuantity().multiply(purchaseItem.getUnitPrice())
            );

            return purchaseItemRepository.save(purchaseItem);
        }
        return null;
    }

    public void deletePurchaseItem(Integer id) {
        purchaseItemRepository.deleteById(id);
    }
}
