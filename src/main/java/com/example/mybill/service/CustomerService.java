package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    public Optional<Customer> getCustomerById(Integer id) {
        return customerRepository.findById(id);
    }

    public Customer createCustomer(Customer customer) {
        customer.setCreatedAt(LocalDateTime.now());
        return customerRepository.save(customer);
    }

    public Customer updateCustomer(Integer id, Customer details) {
        return customerRepository.findById(id).map(c -> {
            c.setCustomerName(details.getCustomerName());
            c.setPhone(details.getPhone());
            c.setAddress(details.getAddress());
            return customerRepository.save(c);
        }).orElse(null);
    }

    public void deleteCustomer(Integer id) {
        customerRepository.deleteById(id);
    }

    public List<Customer> searchCustomers(String term) {
        if (term == null || term.isBlank()) return customerRepository.findAll();
        List<Customer> byPhone = customerRepository.findByPhoneContaining(term);
        List<Customer> byName = customerRepository.findByCustomerNameContainingIgnoreCase(term);
        byName.stream().filter(c -> byPhone.stream().noneMatch(p -> p.getCustomerId().equals(c.getCustomerId())))
              .forEach(byPhone::add);
        return byPhone;
    }
}