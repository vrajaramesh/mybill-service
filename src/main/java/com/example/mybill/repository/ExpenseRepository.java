package com.example.mybill.repository;

import com.example.mybill.dto.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Integer> {

    List<Expense> findByExpenseDateBetweenOrderByExpenseDateDesc(LocalDate from, LocalDate to);

    List<Expense> findAllByOrderByExpenseDateDesc();

    @Query("SELECT e.category, SUM(e.amount) FROM Expense e WHERE e.expenseDate >= :from AND e.expenseDate <= :to GROUP BY e.category")
    List<Object[]> findCategorySumsBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
