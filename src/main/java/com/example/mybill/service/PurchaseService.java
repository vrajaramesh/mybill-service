package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        purchase.setCreatedAt(LocalDateTime.now());

        if (purchase.getPurchaseItems() != null) {
            for (PurchaseItem item : purchase.getPurchaseItems()) {
                item.setPurchase(purchase);
                item.setTotalPrice(null);
                calculateItemFinalPrice(item);
            }
        }

        calculatePurchaseTotals(purchase);
        updatePaymentStatus(purchase);

        return purchaseRepository.save(purchase);
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
                purchase.getPurchaseItems().clear();

                for (PurchaseItem item : purchaseDetails.getPurchaseItems()) {
                    item.setPurchase(purchase);
                    item.setTotalPrice(null);
                    calculateItemFinalPrice(item);
                    purchase.getPurchaseItems().add(item);
                }

                calculatePurchaseTotals(purchase);
            } else if (purchaseDetails.getTotalAmount() != null) {
                purchase.setTotalAmount(purchaseDetails.getTotalAmount());
                purchase.setGst(purchaseDetails.getGst());
                purchase.setFinalAmount(purchaseDetails.getFinalAmount());
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

    private void calculateItemFinalPrice(PurchaseItem item) {
        BigDecimal gstRate = item.getGst() != null ? item.getGst() : new BigDecimal("5");
        BigDecimal basePrice = item.getQuantity().multiply(item.getUnitPrice());
        BigDecimal gstAmount = basePrice.multiply(gstRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        item.setFinalPrice(basePrice.add(gstAmount).setScale(2, RoundingMode.HALF_UP));
        if (item.getGst() == null) {
            item.setGst(gstRate);
        }
    }

    private void calculatePurchaseTotals(Purchase purchase) {
        if (purchase.getPurchaseItems() == null || purchase.getPurchaseItems().isEmpty()) {
            return;
        }
        BigDecimal baseTotal = BigDecimal.ZERO;
        BigDecimal gstTotal = BigDecimal.ZERO;
        for (PurchaseItem item : purchase.getPurchaseItems()) {
            BigDecimal itemBase = item.getQuantity().multiply(item.getUnitPrice());
            BigDecimal itemGst = item.getGst() != null ? item.getGst() : new BigDecimal("5");
            BigDecimal itemGstAmt = itemBase.multiply(itemGst).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            baseTotal = baseTotal.add(itemBase);
            gstTotal = gstTotal.add(itemGstAmt);
        }
        purchase.setTotalAmount(baseTotal.setScale(2, RoundingMode.HALF_UP));
        purchase.setGst(gstTotal.setScale(2, RoundingMode.HALF_UP));
        purchase.setFinalAmount(baseTotal.add(gstTotal).setScale(2, RoundingMode.HALF_UP));
    }

    private void updatePaymentStatus(Purchase purchase) {
        if (purchase.getPaidAmount() == null) {
            purchase.setPaidAmount(BigDecimal.ZERO);
        }

        BigDecimal invoiceTotal = purchase.getFinalAmount() != null
            ? purchase.getFinalAmount() : purchase.getTotalAmount();
        if (invoiceTotal == null) {
            purchase.setPaymentStatus("PENDING");
            return;
        }

        int comparison = purchase.getPaidAmount().compareTo(invoiceTotal);
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
