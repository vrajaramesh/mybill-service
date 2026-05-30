package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @Autowired
    private ReportService reportService;

    @Autowired
    private ChatSummaryService chatSummaryService;

    @GetMapping("/overview")
    public Map<String, Object> getOverview(@RequestParam int year) {
        return reportService.getOverview(year);
    }

    @GetMapping("/monthly-sales")
    public List<Map<String, Object>> getMonthlySales(@RequestParam int year) {
        return reportService.getMonthlySales(year);
    }

    @GetMapping("/top-products")
    public List<Map<String, Object>> getTopProducts(
            @RequestParam int year,
            @RequestParam(defaultValue = "10") int limit) {
        return reportService.getTopProducts(year, limit);
    }

    @GetMapping("/category-revenue")
    public List<Map<String, Object>> getCategoryRevenue(@RequestParam int year) {
        return reportService.getCategoryRevenue(year);
    }

    @GetMapping("/top-customers")
    public List<Map<String, Object>> getTopCustomers(
            @RequestParam int year,
            @RequestParam(defaultValue = "10") int limit) {
        return reportService.getTopCustomers(year, limit);
    }

    @GetMapping("/payment-methods")
    public List<Map<String, Object>> getPaymentMethods(@RequestParam int year) {
        return reportService.getPaymentMethods(year);
    }

    @GetMapping("/purchases-vs-sales")
    public List<Map<String, Object>> getPurchasesVsSales(@RequestParam int year) {
        return reportService.getPurchasesVsSales(year);
    }

    @GetMapping("/customer-sales")
    public Map<String, Object> getCustomerSales(
            @RequestParam(defaultValue = "month") String period) {
        return reportService.getCustomerSales(period);
    }

    @GetMapping("/user-sales")
    public Map<String, Object> getUserSales(
            @RequestParam(defaultValue = "month") String period) {
        return reportService.getUserSales(period);
    }

    @GetMapping("/chat-analytics")
    public Map<String, Object> getChatAnalytics(
            @RequestParam(defaultValue = "month") String period) {
        return reportService.getChatAnalytics(period);
    }

    @GetMapping("/low-stock")
    public List<Map<String, Object>> getLowStock() {
        return reportService.getLowStock();
    }

    @GetMapping("/chat-summary")
    public Map<String, Object> getChatSummary(
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(defaultValue = "false") boolean force) {
        return chatSummaryService.getSummary(period, force);
    }
}
