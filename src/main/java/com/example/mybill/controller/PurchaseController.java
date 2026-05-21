package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private PurchaseItemService purchaseItemService;

    @Autowired
    private PurchasePaymentService purchasePaymentService;

    @GetMapping
    public List<Purchase> getAllPurchases() {
        return purchaseService.getAllPurchases();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Purchase> getPurchaseById(@PathVariable Integer id) {
        Optional<Purchase> purchase = purchaseService.getPurchaseById(id);
        if (purchase.isPresent()) {
            return ResponseEntity.ok(purchase.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public Purchase createPurchase(@RequestBody Purchase purchase) {
        return purchaseService.createPurchase(purchase);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Purchase> updatePurchase(@PathVariable Integer id, @RequestBody Purchase purchaseDetails) {
        Purchase updatedPurchase = purchaseService.updatePurchase(id, purchaseDetails);
        if (updatedPurchase != null) {
            return ResponseEntity.ok(updatedPurchase);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePurchase(@PathVariable Integer id) {
        purchaseService.deletePurchase(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/supplier/{supplierId}")
    public List<Purchase> getPurchasesBySupplier(@PathVariable Integer supplierId) {
        return purchaseService.getPurchasesBySupplier(supplierId);
    }

    @GetMapping("/status/{status}")
    public List<Purchase> getPurchasesByPaymentStatus(@PathVariable String status) {
        return purchaseService.getPurchasesByPaymentStatus(status);
    }

    // Purchase Items endpoints
    @GetMapping("/{purchaseId}/items")
    public List<PurchaseItem> getPurchaseItems(@PathVariable Integer purchaseId) {
        return purchaseItemService.getPurchaseItemsByPurchase(purchaseId);
    }

    @PostMapping("/{purchaseId}/items")
    public PurchaseItem addPurchaseItem(@PathVariable Integer purchaseId, @RequestBody PurchaseItem purchaseItem) {
        Optional<Purchase> purchase = purchaseService.getPurchaseById(purchaseId);
        if (purchase.isPresent()) {
            purchaseItem.setPurchase(purchase.get());
            return purchaseItemService.createPurchaseItem(purchaseItem);
        }
        return null;
    }

    // Purchase Payments endpoints
    @GetMapping("/{purchaseId}/payments")
    public List<PurchasePayment> getPurchasePayments(@PathVariable Integer purchaseId) {
        return purchasePaymentService.getPaymentsByPurchase(purchaseId);
    }

    @PostMapping("/{purchaseId}/payments")
    public PurchasePayment addPurchasePayment(@PathVariable Integer purchaseId, @RequestBody PurchasePayment purchasePayment) {
        return purchaseService.addPayment(purchaseId, purchasePayment);
    }
}
