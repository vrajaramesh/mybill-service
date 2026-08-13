package com.example.mybill.controller;

import com.example.mybill.dto.Expense;
import com.example.mybill.service.ExpenseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    @Autowired
    private ExpenseService expenseService;

    @GetMapping
    public List<Expense> getExpenses(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from != null && to != null) {
            return expenseService.getExpensesByDateRange(from, to);
        }
        return expenseService.getAllExpenses();
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null) from = LocalDate.now().minusDays(30);
        if (to == null) to = LocalDate.now();
        return expenseService.getCategorySummary(from, to);
    }

    @PostMapping
    public Expense createExpense(@RequestBody Expense expense) {
        return expenseService.createExpense(expense);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Expense> updateExpense(@PathVariable Integer id, @RequestBody Expense details) {
        try {
            return ResponseEntity.ok(expenseService.updateExpense(id, details));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Integer id) {
        expenseService.deleteExpense(id);
        return ResponseEntity.noContent().build();
    }
}
