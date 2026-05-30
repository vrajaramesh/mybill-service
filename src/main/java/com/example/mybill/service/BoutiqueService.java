package com.example.mybill.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class BoutiqueService {

    @Autowired private StitchingOrderRepository orderRepo;
    @Autowired private StitchingOrderItemRepository itemRepo;
    @Autowired private CustomerMeasurementRepository measurementRepo;
    @Autowired private BoutiqueOrderImageRepository imageRepo;
    @Autowired private AppUserRepository appUserRepository; // just to satisfy Spring
    @PersistenceContext private EntityManager em;

    // ── Orders ────────────────────────────────────────────

    public List<StitchingOrder> getOrders(String status, String from, String to) {
        LocalDate f = (from != null && !from.isBlank()) ? LocalDate.parse(from) : null;
        LocalDate t = (to   != null && !to.isBlank())   ? LocalDate.parse(to)   : null;
        String st = (status != null && !status.isBlank() && !status.equals("ALL")) ? status : null;
        return orderRepo.findFiltered(st, f, t);
    }

    public Optional<StitchingOrder> getOrderById(Integer id) {
        return orderRepo.findById(id);
    }

    public StitchingOrder createOrder(StitchingOrder order) {
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        if (order.getOrderDate() == null) order.setOrderDate(LocalDate.now());

        // Link items to order
        for (StitchingOrderItem item : order.getItems()) {
            item.setOrder(order);
        }

        // Compute totals from items
        BigDecimal total = order.getItems().stream()
            .map(i -> i.getStitchingCharges().multiply(BigDecimal.valueOf(i.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(total);

        BigDecimal advance = order.getAdvancePaid() != null ? order.getAdvancePaid() : BigDecimal.ZERO;
        order.setAdvancePaid(advance);
        order.setBalanceAmount(total.subtract(advance));

        StitchingOrder saved = orderRepo.save(order);

        // Generate order number after save (so we have the ID)
        String year = String.valueOf(LocalDate.now().getYear());
        saved.setOrderNumber("BQ-" + year + "-" + String.format("%04d", saved.getOrderId()));
        orderRepo.save(saved);

        // Record advance payment if any
        if (advance.compareTo(BigDecimal.ZERO) > 0) {
            BoutiquePayment pmt = new BoutiquePayment();
            pmt.setOrder(saved);
            pmt.setPaymentDate(LocalDate.now());
            pmt.setAmount(advance);
            pmt.setPaymentMethod(order.getNotes() != null && order.getNotes().contains("UPI") ? "UPI" : "CASH");
            pmt.setPaymentType("ADVANCE");
            pmt.setCreatedAt(LocalDateTime.now());
            saved.getPayments().add(pmt);
            orderRepo.save(saved);
        }

        return saved;
    }

    public StitchingOrder updateOrder(Integer id, StitchingOrder details) {
        return orderRepo.findById(id).map(order -> {
            order.setDeliveryDate(details.getDeliveryDate());
            order.setPriority(details.getPriority());
            order.setNotes(details.getNotes());
            order.setUpdatedAt(LocalDateTime.now());

            if (details.getStatus() != null) order.setStatus(details.getStatus());

            // Replace items
            order.getItems().clear();
            for (StitchingOrderItem item : details.getItems()) {
                item.setOrder(order);
                order.getItems().add(item);
            }

            BigDecimal itemsTotal = order.getItems().stream()
                .map(i -> i.getStitchingCharges().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal extraTotal = order.getExtraCharges().stream()
                .map(ExtraCharge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal total = itemsTotal.add(extraTotal);
            order.setTotalAmount(total);

            BigDecimal paid = order.getPayments().stream()
                .map(BoutiquePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            order.setAdvancePaid(paid);
            order.setBalanceAmount(total.subtract(paid));

            return orderRepo.save(order);
        }).orElse(null);
    }

    public StitchingOrder updateStatus(Integer id, String status) {
        return orderRepo.findById(id).map(order -> {
            order.setStatus(status);
            order.setUpdatedAt(LocalDateTime.now());
            return orderRepo.save(order);
        }).orElse(null);
    }

    public void deleteOrder(Integer id) {
        orderRepo.deleteById(id);
    }

    public StitchingOrder addExtraCharge(Integer orderId, ExtraCharge charge) {
        return orderRepo.findById(orderId).map(order -> {
            charge.setOrder(order);
            charge.setAddedAt(LocalDateTime.now());
            order.getExtraCharges().add(charge);

            BigDecimal itemsTotal = order.getItems().stream()
                .map(i -> i.getStitchingCharges().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal extraTotal = order.getExtraCharges().stream()
                .map(ExtraCharge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal newTotal = itemsTotal.add(extraTotal);
            order.setTotalAmount(newTotal);

            BigDecimal paid = order.getPayments().stream()
                .map(BoutiquePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            order.setAdvancePaid(paid);
            order.setBalanceAmount(newTotal.subtract(paid));
            order.setUpdatedAt(LocalDateTime.now());

            return orderRepo.save(order);
        }).orElse(null);
    }

    public StitchingOrder addPayment(Integer orderId, BoutiquePayment payment) {
        return orderRepo.findById(orderId).map(order -> {
            payment.setOrder(order);
            payment.setCreatedAt(LocalDateTime.now());
            if (payment.getPaymentDate() == null) payment.setPaymentDate(LocalDate.now());
            order.getPayments().add(payment);

            BigDecimal paid = order.getPayments().stream()
                .map(BoutiquePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            order.setAdvancePaid(paid);
            order.setBalanceAmount(order.getTotalAmount().subtract(paid));
            order.setUpdatedAt(LocalDateTime.now());

            return orderRepo.save(order);
        }).orElse(null);
    }

    // ── Measurements ──────────────────────────────────────

    public List<CustomerMeasurement> getMeasurements(Integer customerId) {
        return measurementRepo.findByCustomer_CustomerIdOrderByCreatedAtDesc(customerId);
    }

    public CustomerMeasurement saveMeasurement(CustomerMeasurement m) {
        if (m.getMeasurementId() == null) {
            m.setCreatedAt(LocalDateTime.now());
        }
        m.setUpdatedAt(LocalDateTime.now());
        return measurementRepo.save(m);
    }

    public void deleteMeasurement(Integer id) {
        measurementRepo.deleteById(id);
    }

    // ── Item Images ───────────────────────────────────────

    public List<BoutiqueOrderImage> getItemImages(Integer itemId) {
        return imageRepo.findByItemItemIdOrderByCreatedAtAsc(itemId);
    }

    public BoutiqueOrderImage addItemImage(Integer itemId, String imageUrl, String publicId) {
        return itemRepo.findById(itemId).map(item -> {
            BoutiqueOrderImage img = new BoutiqueOrderImage();
            img.setItem(item);
            img.setImageUrl(imageUrl);
            img.setPublicId(publicId);
            img.setCreatedAt(LocalDateTime.now());
            return imageRepo.save(img);
        }).orElse(null);
    }

    public void deleteItemImage(Integer imageId) {
        imageRepo.deleteById(imageId);
    }

    // ── Reports / Summary ─────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSummary() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Status counts
        String[] statuses = {"RECEIVED","CUTTING","STITCHING","FINISHING","READY","DELIVERED","CANCELLED"};
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : statuses) counts.put(s, orderRepo.countByStatus(s));
        result.put("statusCounts", counts);

        long active = counts.getOrDefault("RECEIVED",0L) + counts.getOrDefault("CUTTING",0L)
                    + counts.getOrDefault("STITCHING",0L) + counts.getOrDefault("FINISHING",0L);
        result.put("activeOrders", active);
        result.put("readyForPickup", counts.getOrDefault("READY", 0L));

        // Overdue orders
        List<StitchingOrder> overdue = orderRepo.findOverdueOrders(LocalDate.now());
        result.put("overdueCount", overdue.size());

        // This month revenue
        Object[] rev = (Object[]) em.createNativeQuery(
            "SELECT COALESCE(SUM(total_amount),0), COUNT(*) FROM stitching_orders " +
            "WHERE EXTRACT(YEAR FROM order_date) = EXTRACT(YEAR FROM CURRENT_DATE) " +
            "AND EXTRACT(MONTH FROM order_date) = EXTRACT(MONTH FROM CURRENT_DATE)"
        ).getSingleResult();
        result.put("thisMonthRevenue", rev[0]);
        result.put("thisMonthOrders", ((Number) rev[1]).longValue());

        // Monthly revenue trend (current year)
        List<Object[]> monthly = em.createNativeQuery(
            "SELECT EXTRACT(MONTH FROM order_date), SUM(total_amount), COUNT(*) " +
            "FROM stitching_orders " +
            "WHERE EXTRACT(YEAR FROM order_date) = EXTRACT(YEAR FROM CURRENT_DATE) " +
            "GROUP BY 1 ORDER BY 1"
        ).getResultList();
        result.put("monthlyRevenue", monthly.stream().map(r -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("month", ((Number) r[0]).intValue());
            m.put("revenue", r[1]);
            m.put("orders", ((Number) r[2]).longValue());
            return m;
        }).toList());

        // Popular garment types
        List<Object[]> garments = em.createNativeQuery(
            "SELECT garment_type, COUNT(*), SUM(stitching_charges) " +
            "FROM stitching_order_items GROUP BY garment_type ORDER BY COUNT(*) DESC LIMIT 8"
        ).getResultList();
        result.put("garmentStats", garments.stream().map(r -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("garmentType", r[0]);
            m.put("count", ((Number) r[1]).longValue());
            m.put("revenue", r[2]);
            return m;
        }).toList());

        // Overdue orders list
        result.put("overdueOrders", overdue);

        // Upcoming deliveries (next 7 days)
        List<StitchingOrder> upcoming = em.createQuery(
            "SELECT o FROM StitchingOrder o WHERE o.deliveryDate BETWEEN :today AND :next7 " +
            "AND o.status NOT IN ('DELIVERED','CANCELLED') ORDER BY o.deliveryDate ASC",
            StitchingOrder.class
        ).setParameter("today", LocalDate.now())
         .setParameter("next7", LocalDate.now().plusDays(7))
         .getResultList();
        result.put("upcomingDeliveries", upcoming);

        return result;
    }
}
