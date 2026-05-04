package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class SupplierService {

    @Autowired
    private SupplierRepository supplierRepository;

    public List<Supplier> getAllSuppliers() {
        return supplierRepository.findAll();
    }

    public Optional<Supplier> getSupplierById(Integer id) {
        return supplierRepository.findById(id);
    }

    public Supplier createSupplier(Supplier supplier) {
        supplier.setCreatedAt(LocalDateTime.now());
        return supplierRepository.save(supplier);
    }

    public Supplier updateSupplier(Integer id, Supplier supplierDetails) {
        Optional<Supplier> optionalSupplier = supplierRepository.findById(id);
        if (optionalSupplier.isPresent()) {
            Supplier supplier = optionalSupplier.get();
            supplier.setSupplierName(supplierDetails.getSupplierName());
            supplier.setContactPerson(supplierDetails.getContactPerson());
            supplier.setPhone(supplierDetails.getPhone());
            supplier.setEmail(supplierDetails.getEmail());
            supplier.setAddress(supplierDetails.getAddress());
            return supplierRepository.save(supplier);
        }
        return null;
    }

    public void deleteSupplier(Integer id) {
        supplierRepository.deleteById(id);
    }
}
