package com.example.mybill.service;

import com.example.mybill.dto.Expense;
import com.example.mybill.repository.ExpenseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExpenseService {

    @Autowired
    private ExpenseRepository expenseRepository;

    public List<Expense> getAllExpenses() {
        return expenseRepository.findAllByOrderByExpenseDateDesc();
    }

    public List<Expense> getExpensesByDateRange(LocalDate from, LocalDate to) {
        return expenseRepository.findByExpenseDateBetweenOrderByExpenseDateDesc(from, to);
    }

    @Transactional
    public Expense createExpense(Expense expense) {
        expense.setCreatedAt(LocalDateTime.now());
        return expenseRepository.save(expense);
    }

    @Transactional
    public Expense updateExpense(Integer id, Expense details) {
        Expense existing = expenseRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Expense not found: " + id));
        existing.setExpenseDate(details.getExpenseDate());
        existing.setCategory(details.getCategory());
        existing.setDescription(details.getDescription());
        existing.setAmount(details.getAmount());
        return expenseRepository.save(existing);
    }

    @Transactional
    public void deleteExpense(Integer id) {
        expenseRepository.deleteById(id);
    }

    public Map<String, Object> getCategorySummary(LocalDate from, LocalDate to) {
        List<Object[]> rows = expenseRepository.findCategorySumsBetween(from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        double grandTotal = 0;
        for (Object[] row : rows) {
            String category = (String) row[0];
            double amount = ((Number) row[1]).doubleValue();
            result.put(category, amount);
            grandTotal += amount;
        }
        result.put("TOTAL", grandTotal);
        return result;
    }
}
