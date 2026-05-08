package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BillService {

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private BillItemRepository billItemRepository;

    @Autowired
    private ProductRepository productRepository;

    public List<Bill> getAllBills() {
        return billRepository.findAllOrderByCreatedAtDesc();
    }

    public Optional<Bill> getBillById(Integer id) {
        return billRepository.findById(id);
    }

    @Transactional
    public Bill createBill(Bill bill) {
        bill.setBillNumber(generateBillNumber());
        bill.setBillDate(bill.getBillDate() != null ? bill.getBillDate() : LocalDate.now());
        bill.setCreatedAt(LocalDateTime.now());

        if (bill.getBillItems() != null) {
            for (BillItem item : bill.getBillItems()) {
                item.setBill(bill);
                calculateBillItem(item);
            }
        }

        calculateBillTotals(bill);
        Bill saved = billRepository.save(bill);

        // Deduct stock after saving
        if (saved.getBillItems() != null) {
            for (BillItem item : saved.getBillItems()) {
                deductStock(item);
            }
        }

        return saved;
    }

    @Transactional
    public Bill updateBill(Integer id, Bill details) {
        return billRepository.findById(id).map(bill -> {
            bill.setBillDate(details.getBillDate());
            bill.setPaymentMethod(details.getPaymentMethod());
            bill.setNotes(details.getNotes());
            bill.setCustomer(details.getCustomer());

            if (details.getBillItems() != null) {
                bill.getBillItems().clear();
                for (BillItem item : details.getBillItems()) {
                    item.setBill(bill);
                    calculateBillItem(item);
                    bill.getBillItems().add(item);
                }
                calculateBillTotals(bill);
            }

            return billRepository.save(bill);
        }).orElse(null);
    }

    public void deleteBill(Integer id) {
        billRepository.deleteById(id);
    }

    private void calculateBillItem(BillItem item) {
        BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
        BigDecimal price = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal discPct = item.getDiscountPct() != null ? item.getDiscountPct() : BigDecimal.ZERO;
        BigDecimal gstPct = item.getGstPct() != null ? item.getGstPct() : new BigDecimal("5");

        // Total after discount (GST inclusive)
        BigDecimal lineTotal = qty.multiply(price)
            .multiply(BigDecimal.ONE.subtract(discPct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)))
            .setScale(2, RoundingMode.HALF_UP);

        // Taxable = lineTotal / (1 + gstPct/100)
        BigDecimal divisor = BigDecimal.ONE.add(gstPct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
        BigDecimal taxable = lineTotal.divide(divisor, 2, RoundingMode.HALF_UP);
        BigDecimal gstAmt = lineTotal.subtract(taxable).setScale(2, RoundingMode.HALF_UP);

        item.setTotalPrice(lineTotal);
        item.setTaxableAmount(taxable);
        item.setGstAmount(gstAmt);
        item.setGstPct(gstPct);
    }

    private void calculateBillTotals(Bill bill) {
        if (bill.getBillItems() == null || bill.getBillItems().isEmpty()) {
            bill.setSubtotal(BigDecimal.ZERO);
            bill.setGstAmount(BigDecimal.ZERO);
            bill.setTotalAmount(BigDecimal.ZERO);
            bill.setDiscountAmount(BigDecimal.ZERO);
            return;
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal gstTotal = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (BillItem item : bill.getBillItems()) {
            subtotal = subtotal.add(item.getTaxableAmount());
            gstTotal = gstTotal.add(item.getGstAmount());
            total = total.add(item.getTotalPrice());
        }

        bill.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        bill.setGstAmount(gstTotal.setScale(2, RoundingMode.HALF_UP));
        bill.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        bill.setDiscountAmount(bill.getDiscountAmount() != null ? bill.getDiscountAmount() : BigDecimal.ZERO);
    }

    private void deductStock(BillItem item) {
        if (item.getProduct() == null || item.getProduct().getProductId() == null) return;
        productRepository.findById(item.getProduct().getProductId()).ifPresent(product -> {
            BigDecimal newStock = product.getStockQuantity().subtract(item.getQuantity());
            product.setStockQuantity(newStock.max(BigDecimal.ZERO));
            productRepository.save(product);
        });
    }

    public List<GstReportRow> getGstReport(int year) {
        String[] monthNames = {"", "January", "February", "March", "April", "May", "June",
                               "July", "August", "September", "October", "November", "December"};
        List<Object[]> rows = billRepository.findGstReportByYear(year);
        List<GstReportRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            int yr = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            String startBill = (String) row[2];
            String endBill = (String) row[3];
            long totalBills = ((Number) row[4]).longValue();
            BigDecimal taxable = new BigDecimal(row[5].toString()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal gstAmt = new BigDecimal(row[6].toString()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal halfGst = gstAmt.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);

            GstReportRow r = new GstReportRow();
            r.setYear(yr);
            r.setMonth(month);
            r.setMonthName(monthNames[month]);
            r.setStartingBillNumber(startBill);
            r.setEndingBillNumber(endBill);
            r.setTotalBills(totalBills);
            r.setTotalTaxableAmount(taxable);
            r.setTotalGstAmount(gstAmt);
            r.setSgst(halfGst);
            r.setCgst(halfGst);
            result.add(r);
        }
        return result;
    }

    private synchronized String generateBillNumber() {
        String year = String.valueOf(LocalDate.now().getYear());
        List<String> existing = billRepository.findBillNumbersByYear(year);
        int maxNum = 0;
        for (String billNum : existing) {
            try {
                int n = Integer.parseInt(billNum.substring(billNum.lastIndexOf('-') + 1));
                if (n > maxNum) maxNum = n;
            } catch (NumberFormatException ignored) {}
        }
        return String.format("BILL-%s-%04d", year, maxNum + 1);
    }
}