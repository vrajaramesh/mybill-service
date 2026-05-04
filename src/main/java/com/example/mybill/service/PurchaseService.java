package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PurchaseService {

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PurchaseItemRepository purchaseItemRepository;

    @Autowired
    private PurchasePaymentRepository purchasePaymentRepository;

    @Autowired
    private ProductRepository productRepository;

    public List<Purchase> getAllPurchases() {
        return purchaseRepository.findAllOrderByCreatedAtDesc();
    }

    public Optional<Purchase> getPurchaseById(Integer id) {
        return purchaseRepository.findById(id);
    }

    @Transactional
    public Purchase createPurchase(Purchase purchase) {
        // Set timestamps
        purchase.setCreatedAt(LocalDateTime.now());

        // Calculate total amount from items if not provided
        if (purchase.getTotalAmount() == null && purchase.getPurchaseItems() != null) {
            BigDecimal total = purchase.getPurchaseItems().stream()
                .map(item -> item.getTotalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            purchase.setTotalAmount(total);
        }

        // Update payment status based on paid amount
        updatePaymentStatus(purchase);

        // Save purchase first
        Purchase savedPurchase = purchaseRepository.save(purchase);

        // Save purchase items
        if (purchase.getPurchaseItems() != null) {
            for (PurchaseItem item : purchase.getPurchaseItems()) {
                item.setPurchase(savedPurchase);
                purchaseItemRepository.save(item);
            }
        }

        return savedPurchase;
    }

    @Transactional
    public Purchase updatePurchase(Integer id, Purchase purchaseDetails) {
        Optional<Purchase> optionalPurchase = purchaseRepository.findById(id);
        if (optionalPurchase.isPresent()) {
            Purchase purchase = optionalPurchase.get();

            // Update basic fields
            purchase.setInvoiceNumber(purchaseDetails.getInvoiceNumber());
            purchase.setInvoiceDate(purchaseDetails.getInvoiceDate());
            purchase.setPaymentDueDate(purchaseDetails.getPaymentDueDate());
            purchase.setNotes(purchaseDetails.getNotes());

            // Update supplier if provided
            if (purchaseDetails.getSupplier() != null) {
                purchase.setSupplier(purchaseDetails.getSupplier());
            }

            // Handle purchase items update
            if (purchaseDetails.getPurchaseItems() != null) {
                // Remove existing items
                purchaseItemRepository.deleteByPurchase_PurchaseId(id);

                // Add new items
                for (PurchaseItem item : purchaseDetails.getPurchaseItems()) {
                    item.setPurchase(purchase);
                    purchaseItemRepository.save(item);
                }

                // Recalculate total amount from items
                BigDecimal total = purchaseDetails.getPurchaseItems().stream()
                    .map(item -> item.getQuantity().multiply(item.getUnitPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                purchase.setTotalAmount(total);
            } else if (purchaseDetails.getTotalAmount() != null) {
                // Use provided total amount if no items
                purchase.setTotalAmount(purchaseDetails.getTotalAmount());
            }

            // Update payment status
            updatePaymentStatus(purchase);

            return purchaseRepository.save(purchase);
        }
        return null;
    }

    public void deletePurchase(Integer id) {
        purchaseRepository.deleteById(id);
    }

    public List<Purchase> getPurchasesBySupplier(Integer supplierId) {
        return purchaseRepository.findBySupplier_SupplierId(supplierId);
    }

    public List<Purchase> getPurchasesByPaymentStatus(String paymentStatus) {
        return purchaseRepository.findByPaymentStatus(paymentStatus);
    }

    @Transactional
    public PurchasePayment addPayment(Integer purchaseId, PurchasePayment payment) {
        Optional<Purchase> optionalPurchase = purchaseRepository.findById(purchaseId);
        if (optionalPurchase.isPresent()) {
            Purchase purchase = optionalPurchase.get();
            payment.setPurchase(purchase);

            // Save payment
            PurchasePayment savedPayment = purchasePaymentRepository.save(payment);

            // Update paid amount and status
            BigDecimal totalPaid = purchasePaymentRepository.findByPurchase_PurchaseId(purchaseId)
                .stream()
                .map(PurchasePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            purchase.setPaidAmount(totalPaid);
            updatePaymentStatus(purchase);
            purchaseRepository.save(purchase);

            return savedPayment;
        }
        return null;
    }

    private void updatePaymentStatus(Purchase purchase) {
        if (purchase.getPaidAmount() == null) {
            purchase.setPaidAmount(BigDecimal.ZERO);
        }

        if (purchase.getTotalAmount() == null) {
            purchase.setPaymentStatus("PENDING");
            return;
        }

        int comparison = purchase.getPaidAmount().compareTo(purchase.getTotalAmount());
        if (comparison == 0) {
            purchase.setPaymentStatus("PAID");
        } else if (comparison > 0) {
            purchase.setPaymentStatus("PAID"); // Overpaid, still consider as paid
        } else if (purchase.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            purchase.setPaymentStatus("PARTIAL");
        } else {
            purchase.setPaymentStatus("PENDING");
        }
    }
}
