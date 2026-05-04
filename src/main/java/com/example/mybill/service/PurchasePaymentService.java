package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PurchasePaymentService {

    @Autowired
    private PurchasePaymentRepository purchasePaymentRepository;

    public List<PurchasePayment> getAllPurchasePayments() {
        return purchasePaymentRepository.findAll();
    }

    public Optional<PurchasePayment> getPurchasePaymentById(Integer id) {
        return purchasePaymentRepository.findById(id);
    }

    public List<PurchasePayment> getPaymentsByPurchase(Integer purchaseId) {
        return purchasePaymentRepository.findByPurchase_PurchaseIdOrderByPaymentDateDesc(purchaseId);
    }

    public PurchasePayment createPurchasePayment(PurchasePayment purchasePayment) {
        return purchasePaymentRepository.save(purchasePayment);
    }

    public PurchasePayment updatePurchasePayment(Integer id, PurchasePayment purchasePaymentDetails) {
        Optional<PurchasePayment> optionalPurchasePayment = purchasePaymentRepository.findById(id);
        if (optionalPurchasePayment.isPresent()) {
            PurchasePayment purchasePayment = optionalPurchasePayment.get();
            purchasePayment.setPaymentDate(purchasePaymentDetails.getPaymentDate());
            purchasePayment.setAmount(purchasePaymentDetails.getAmount());
            purchasePayment.setPaymentMethod(purchasePaymentDetails.getPaymentMethod());
            purchasePayment.setNotes(purchasePaymentDetails.getNotes());
            return purchasePaymentRepository.save(purchasePayment);
        }
        return null;
    }

    public void deletePurchasePayment(Integer id) {
        purchasePaymentRepository.deleteById(id);
    }
}
