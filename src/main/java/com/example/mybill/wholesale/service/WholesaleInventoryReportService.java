package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.entity.*;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Wholesale stock views (current stock, ledger, product history, movement summary), stock adjustments and inventory
 * settings. Reads wholesale tables only.
 */
@Service
public class WholesaleInventoryReportService {

    private static final int MAX_LEDGER = 1000;
    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    @Autowired private WholesaleInventoryLedgerRepository ledgerRepository;
    @Autowired private WholesaleProductRepository productRepository;
    @Autowired private WholesaleStockAdjustmentRepository adjustmentRepository;
    @Autowired private WholesaleInventorySettingsRepository settingsRepository;
    @Autowired private WholesaleInventoryService inventoryService;
    @Autowired private WholesaleDocumentNumberService numberService;

    // ── Current stock ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WholesaleCurrentStockRow> currentStock(String q, boolean includeInactive, boolean onlyInStock) {
        Map<Integer, Object[]> net = new HashMap<>();
        for (Object[] row : ledgerRepository.netByProduct()) net.put((Integer) row[0], row);
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<WholesaleCurrentStockRow> rows = new ArrayList<>();
        for (WholesaleProduct p : productRepository.findAllByOrderByProductNameAsc()) {
            if (!includeInactive && !Boolean.TRUE.equals(p.getIsActive())) continue;
            if (onlyInStock && p.getAvailableQuantity().signum() <= 0) continue;
            if (!term.isEmpty() && !(contains(p.getProductName(), term) || contains(p.getProductCode(), term)
                || contains(p.getProductType(), term) || contains(p.getHsnCode(), term))) continue;
            Object[] n = net.get(p.getWholesaleProductId());
            BigDecimal ledgerQty = n != null ? (BigDecimal) n[1] : BigDecimal.ZERO;
            rows.add(new WholesaleCurrentStockRow(p.getWholesaleProductId(), p.getProductCode(), p.getProductName(),
                p.getProductType(), p.getUnit(), p.getIsActive(), p.getAvailableQuantity(), ledgerQty,
                ledgerQty.compareTo(p.getAvailableQuantity()) == 0, p.getPurchaseRate(),
                p.getAvailableQuantity().multiply(p.getPurchaseRate()).setScale(2, RoundingMode.HALF_UP),
                n != null ? (LocalDate) n[2] : null));
        }
        return rows;
    }

    // ── Ledger ───────────────────────────────────────────────

    /** Newest first. All filters optional; q matches the reference number. */
    @Transactional(readOnly = true)
    public List<WholesaleLedgerEntryResponse> ledger(Integer productId, LocalDate from, LocalDate to, String types,
                                                     String referenceType, String q, Integer limit) {
        List<WholesaleInventoryTransactionType> typeList = parseTypes(types);
        Specification<WholesaleInventoryLedgerEntry> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (productId != null) p.add(cb.equal(root.get("product").get("wholesaleProductId"), productId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("transactionDate"), to));
            if (!typeList.isEmpty()) p.add(root.get("transactionType").in(typeList));
            if (referenceType != null && !referenceType.isBlank()) p.add(cb.equal(root.get("referenceType"), referenceType.trim().toUpperCase(Locale.ROOT)));
            if (q != null && !q.isBlank()) p.add(cb.like(cb.lower(root.get("referenceNumber")), "%" + q.trim().toLowerCase(Locale.ROOT) + "%"));
            return cb.and(p.toArray(new Predicate[0]));
        };
        int size = limit == null || limit <= 0 ? 500 : Math.min(limit, MAX_LEDGER);
        return ledgerRepository.findAll(spec, PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "ledgerId"))).stream()
            .map(WholesaleInventoryReportService::toEntry).toList();
    }

    /** Product-wise history with opening/closing balance for the date range (by transaction date). */
    @Transactional(readOnly = true)
    public WholesaleProductStockHistory productHistory(Integer productId, LocalDate from, LocalDate to) {
        WholesaleProduct p = productRepository.findById(productId)
            .orElseThrow(() -> WholesaleException.notFound("Wholesale product #" + productId + " not found"));
        LocalDate fromDate = from != null ? from : MIN_DATE;
        LocalDate toDate = to != null ? to : WholesaleDocumentNumberService.today();
        if (toDate.isBefore(fromDate)) throw WholesaleException.badRequest("'To' date cannot be before 'from' date");
        BigDecimal opening = ledgerRepository.netBefore(productId, fromDate);
        Specification<WholesaleInventoryLedgerEntry> spec = (root, query, cb) -> cb.and(
            cb.equal(root.get("product").get("wholesaleProductId"), productId),
            cb.between(root.get("transactionDate"), fromDate, toDate));
        List<WholesaleLedgerEntryResponse> entries = ledgerRepository.findAll(spec,
                Sort.by(Sort.Order.asc("transactionDate"), Sort.Order.asc("ledgerId"))).stream()
            .map(WholesaleInventoryReportService::toEntry).toList();
        BigDecimal in = entries.stream().map(WholesaleLedgerEntryResponse::quantityIn).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal out = entries.stream().map(WholesaleLedgerEntryResponse::quantityOut).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WholesaleProductStockHistory(p.getWholesaleProductId(), p.getProductCode(), p.getProductName(), p.getUnit(),
            p.getAvailableQuantity(), from != null ? fromDate : null, toDate, opening, in, out, opening.add(in).subtract(out), entries);
    }

    /** Opening / in / out / closing per product for a date range. */
    @Transactional(readOnly = true)
    public List<WholesaleStockMovementRow> movements(LocalDate from, LocalDate to, String q, boolean onlyMoved) {
        LocalDate fromDate = from != null ? from : MIN_DATE;
        LocalDate toDate = to != null ? to : WholesaleDocumentNumberService.today();
        if (toDate.isBefore(fromDate)) throw WholesaleException.badRequest("'To' date cannot be before 'from' date");
        Map<Integer, Object[]> sums = new HashMap<>();
        for (Object[] row : ledgerRepository.movementSummary(fromDate, toDate)) sums.put((Integer) row[0], row);
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<WholesaleStockMovementRow> rows = new ArrayList<>();
        for (WholesaleProduct p : productRepository.findAllByOrderByProductNameAsc()) {
            Object[] s = sums.get(p.getWholesaleProductId());
            BigDecimal opening = s != null ? (BigDecimal) s[1] : BigDecimal.ZERO;
            BigDecimal in = s != null ? (BigDecimal) s[2] : BigDecimal.ZERO;
            BigDecimal out = s != null ? (BigDecimal) s[3] : BigDecimal.ZERO;
            if (onlyMoved && in.signum() == 0 && out.signum() == 0) continue;
            if (s == null && !onlyMoved && !Boolean.TRUE.equals(p.getIsActive())) continue;
            if (!term.isEmpty() && !(contains(p.getProductName(), term) || contains(p.getProductCode(), term))) continue;
            rows.add(new WholesaleStockMovementRow(p.getWholesaleProductId(), p.getProductCode(), p.getProductName(), p.getUnit(),
                opening, in, out, opening.add(in).subtract(out)));
        }
        return rows;
    }

    // ── Adjustments ──────────────────────────────────────────

    /** Manual correction: one ADJUSTMENT ledger row per line, sourced from this adjustment document (all-or-nothing). */
    @Transactional
    public WholesaleStockAdjustmentResponse createAdjustment(WholesaleStockAdjustmentRequest r, String username) {
        LocalDate today = WholesaleDocumentNumberService.today();
        if (r.adjustmentDate().isAfter(today)) throw WholesaleException.badRequest("Adjustment date cannot be in the future");
        WholesaleStockAdjustment a = new WholesaleStockAdjustment();
        a.setAdjustmentDate(r.adjustmentDate());
        a.setReason(r.reason().trim());
        a.setCreatedBy(username);
        int n = 1;
        for (WholesaleStockAdjustmentRequest.Line line : r.items()) {
            if (line.quantityChange().signum() == 0) throw WholesaleException.badRequest("Line " + n + ": quantity change cannot be 0");
            WholesaleProduct p = productRepository.findById(line.wholesaleProductId())
                .orElseThrow(() -> WholesaleException.badRequest("Wholesale product #" + line.wholesaleProductId() + " not found"));
            WholesaleStockAdjustmentItem item = new WholesaleStockAdjustmentItem();
            item.setAdjustment(a);
            item.setProduct(p);
            item.setQuantityChange(line.quantityChange());
            item.setNotes(WholesaleProductService.blankToNull(line.notes()));
            a.getItems().add(item);
            n++;
        }
        a.setAdjustmentNumber(numberService.next(WholesaleDocumentSettings.STOCK_ADJUSTMENT, r.adjustmentDate(),
            adjustmentRepository::existsByAdjustmentNumber));
        WholesaleStockAdjustment saved = adjustmentRepository.saveAndFlush(a);

        List<WholesaleInventoryService.Movement> movements = new ArrayList<>();
        for (WholesaleStockAdjustmentItem i : saved.getItems()) {
            String note = i.getNotes() != null ? saved.getReason() + " — " + i.getNotes() : saved.getReason();
            movements.add(i.getQuantityChange().signum() > 0
                ? WholesaleInventoryService.Movement.in(i.getProduct(), WholesaleInventoryTransactionType.ADJUSTMENT,
                    "STOCK_ADJUSTMENT", saved.getAdjustmentId(), saved.getAdjustmentNumber(), i.getQuantityChange(),
                    i.getProduct().getPurchaseRate(), saved.getAdjustmentDate(), note)
                : WholesaleInventoryService.Movement.out(i.getProduct(), WholesaleInventoryTransactionType.ADJUSTMENT,
                    "STOCK_ADJUSTMENT", saved.getAdjustmentId(), saved.getAdjustmentNumber(), i.getQuantityChange().negate(),
                    i.getProduct().getPurchaseRate(), saved.getAdjustmentDate(), note));
        }
        inventoryService.post(movements, username);
        return toAdjustment(saved);
    }

    @Transactional(readOnly = true)
    public List<WholesaleStockAdjustmentResponse> adjustments() {
        return adjustmentRepository.findTop200ByOrderByAdjustmentDateDescAdjustmentIdDesc().stream()
            .map(WholesaleInventoryReportService::toAdjustment).toList();
    }

    @Transactional(readOnly = true)
    public WholesaleStockAdjustmentResponse adjustment(Integer id) {
        return toAdjustment(adjustmentRepository.findById(id)
            .orElseThrow(() -> WholesaleException.notFound("Stock adjustment #" + id + " not found")));
    }

    // ── Settings ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public WholesaleInventorySettingsDto settings() {
        WholesaleInventorySettings s = settingsRepository.findById(WholesaleInventorySettings.SINGLETON_ID).orElseGet(WholesaleInventorySettings::new);
        return new WholesaleInventorySettingsDto(s.getAllowNegativeStock(), s.getUpdatedAt(), s.getUpdatedBy());
    }

    @Transactional
    public WholesaleInventorySettingsDto updateSettings(boolean allowNegativeStock, String username) {
        WholesaleInventorySettings s = settingsRepository.findById(WholesaleInventorySettings.SINGLETON_ID).orElseGet(WholesaleInventorySettings::new);
        s.setAllowNegativeStock(allowNegativeStock);
        s.setUpdatedBy(username);
        settingsRepository.save(s);
        return new WholesaleInventorySettingsDto(s.getAllowNegativeStock(), LocalDateTime.now(), username);
    }

    // ── Mapping ──────────────────────────────────────────────

    private static List<WholesaleInventoryTransactionType> parseTypes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<WholesaleInventoryTransactionType> out = new ArrayList<>();
        for (String t : raw.split(",")) {
            try {
                out.add(WholesaleInventoryTransactionType.valueOf(t.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw WholesaleException.badRequest("Unknown transaction type " + t.trim());
            }
        }
        return out;
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    static WholesaleLedgerEntryResponse toEntry(WholesaleInventoryLedgerEntry e) {
        WholesaleProduct p = e.getProduct();
        return new WholesaleLedgerEntryResponse(e.getLedgerId(), p.getWholesaleProductId(), p.getProductCode(), p.getProductName(),
            e.getUnit(), e.getTransactionType().name(), e.getReferenceType(), e.getReferenceId(), e.getReferenceNumber(),
            e.getReversesLedgerId(), e.getQuantityIn(), e.getQuantityOut(), e.getBalanceQuantity(), e.getRate(),
            e.getTransactionDate(), e.getNotes(), e.getCreatedAt(), e.getCreatedBy());
    }

    private static WholesaleStockAdjustmentResponse toAdjustment(WholesaleStockAdjustment a) {
        return new WholesaleStockAdjustmentResponse(a.getAdjustmentId(), a.getAdjustmentNumber(), a.getAdjustmentDate(),
            a.getReason(), a.getIsOpening(), a.getCreatedAt(), a.getCreatedBy(),
            a.getItems().stream().map(i -> new WholesaleStockAdjustmentResponse.Line(i.getProduct().getWholesaleProductId(),
                i.getProduct().getProductName(), i.getProduct().getUnit(), i.getQuantityChange(), i.getNotes())).toList());
    }
}
