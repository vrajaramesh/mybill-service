package com.example.mybill.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillRepository extends JpaRepository<Bill, Integer> {

    @Query("SELECT b FROM Bill b ORDER BY b.createdAt DESC")
    List<Bill> findAllOrderByCreatedAtDesc();

    @Query("SELECT b.billNumber FROM Bill b WHERE b.billNumber LIKE CONCAT('BILL-', :year, '-%') ORDER BY b.billNumber DESC")
    List<String> findBillNumbersByYear(@Param("year") String year);

    @Query(value = "SELECT CAST(EXTRACT(YEAR FROM bill_date) AS integer) AS year, " +
                   "CAST(EXTRACT(MONTH FROM bill_date) AS integer) AS month, " +
                   "MIN(bill_number) AS starting_bill_number, MAX(bill_number) AS ending_bill_number, " +
                   "CAST(COUNT(*) AS bigint) AS total_bills, " +
                   "COALESCE(SUM(subtotal), 0) AS total_taxable_amount, " +
                   "COALESCE(SUM(gst_amount), 0) AS total_gst_amount " +
                   "FROM bills WHERE CAST(EXTRACT(YEAR FROM bill_date) AS integer) = :year " +
                   "GROUP BY CAST(EXTRACT(YEAR FROM bill_date) AS integer), CAST(EXTRACT(MONTH FROM bill_date) AS integer) " +
                   "ORDER BY CAST(EXTRACT(MONTH FROM bill_date) AS integer)", nativeQuery = true)
    List<Object[]> findGstReportByYear(@Param("year") int year);
}