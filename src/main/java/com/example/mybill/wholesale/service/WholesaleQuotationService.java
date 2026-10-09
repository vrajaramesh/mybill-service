package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.entity.*;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleCustomerRepository;
import com.example.mybill.wholesale.repository.WholesaleProductPriceRuleRepository;
import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import com.example.mybill.wholesale.repository.WholesaleQuotationRepository;
import com.example.mybill.wholesale.repository.WholesaleSalesDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Wholesale quotations.
 *
 * Pricing: each line's rate comes from the product's quantity price rule on the quotation date (or a manual
 * override) and is STORED on the line, together with name/HSN/unit/GST snapshots. Nothing ever re-prices a saved
 * quotation automatically, so later price-rule, product, customer or business-profile changes do not alter it.
 * Retail products / retail pricing are never used.
 */
@Service
public class WholesaleQuotationService {

    private static final int MAX_LIST = 500;
    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2999, 12, 31);

    @Autowired private WholesaleQuotationRepository quotationRepository;
    @Autowired private WholesaleCustomerRepository customerRepository;
    @Autowired private WholesaleProductRepository productRepository;
    @Autowired private WholesaleProductPriceRuleRepository ruleRepository;
    @Autowired private WholesaleBusinessProfileService profileService;
    @Autowired private WholesaleDocumentNumberService numberService;
    @Autowired private WholesaleSalesDocumentRepository salesDocumentRepository;
    @Autowired private WholesaleLinePricingService linePricing;
    @Autowired private ObjectMapper objectMapper;

    // ── Queries ──────────────────────────────────────────────

    @Transactional
    public List<WholesaleQuotationSummary> list(String q, String status, LocalDate from, LocalDate to, Integer limit) {
        expireOverdue();
        List<WholesaleQuotationStatus> statuses = parseStatuses(status);
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int size = limit == null || limit <= 0 ? MAX_LIST : Math.min(limit, MAX_LIST);
        return quotationRepository.search(statuses, from != null ? from : MIN_DATE, to != null ? to : MAX_DATE,
                "%" + term + "%", PageRequest.of(0, size)).stream()
            .map(qt -> new WholesaleQuotationSummary(qt.getQuotationId(), qt.getQuotationNumber(), displayNumber(qt),
                qt.getStatus().name(), qt.getQuotationDate(), qt.getValidUntil(), qt.getCustomer().getWholesaleCustomerId(),
                qt.getCustomerName(), qt.getCustomerBusinessName(), qt.getGrandTotal()))
            .toList();
    }

    @Transactional
    public WholesaleQuotationResponse get(Integer id) {
        expireOverdue();
        return toResponse(find(id), List.of());
    }

    /** Calculates a quotation exactly as save would, without saving (used by the editor for live totals). */
    @Transactional(readOnly = true)
    public WholesaleQuotationResponse preview(WholesaleQuotationRequest request) {
        WholesaleQuotation draft = new WholesaleQuotation();
        List<String> warnings = new ArrayList<>();
        apply(draft, request, warnings);
        return toResponse(draft, warnings);
    }

    // ── Commands ─────────────────────────────────────────────

    @Transactional
    public WholesaleQuotationResponse create(WholesaleQuotationRequest request, String username) {
        WholesaleQuotation quotation = new WholesaleQuotation();
        quotation.setCreatedBy(username);
        List<String> warnings = new ArrayList<>();
        apply(quotation, request, warnings);
        return toResponse(quotationRepository.save(quotation), warnings);
    }

    @Transactional
    public WholesaleQuotationResponse update(Integer id, WholesaleQuotationRequest request) {
        WholesaleQuotation quotation = find(id);
        requireStatus(quotation, WholesaleQuotationStatus.DRAFT, "edited");
        List<String> warnings = new ArrayList<>();
        apply(quotation, request, warnings);
        return toResponse(quotationRepository.save(quotation), warnings);
    }

    @Transactional
    public void delete(Integer id) {
        WholesaleQuotation quotation = find(id);
        requireStatus(quotation, WholesaleQuotationStatus.DRAFT, "deleted");
        quotationRepository.delete(quotation);
    }

    /**
     * New DRAFT copied from any quotation, dated today. reprice = true applies today's price rules
     * (lines with no applicable rule keep their old rate); false keeps the original rates.
     */
    @Transactional
    public WholesaleQuotationResponse duplicate(Integer id, boolean reprice, String username) {
        WholesaleQuotation source = find(id);
        LocalDate today = WholesaleDocumentNumberService.today();
        List<WholesaleQuotationItemRequest> items = new ArrayList<>();
        for (WholesaleQuotationItem it : source.getItems()) {
            boolean ruleExists = reprice && !ruleRepository
                .findApplicable(it.getProduct().getWholesaleProductId(), it.getQuantity(), today).isEmpty();
            items.add(new WholesaleQuotationItemRequest(it.getProduct().getWholesaleProductId(), it.getQuantity(),
                ruleExists ? null : it.getRate(), it.getDiscountPct(), ruleExists ? null : it.getGstPct(),
                it.getDescription()));
        }
        WholesaleQuotationRequest request = new WholesaleQuotationRequest(source.getCustomer().getWholesaleCustomerId(),
            today, null, source.getOtherChargesLabel(), source.getOtherChargesAmount(), source.getOtherChargesGstPct(),
            source.getNotes(), source.getTerms(), items);

        WholesaleQuotation copy = new WholesaleQuotation();
        copy.setCreatedBy(username);
        copy.setDuplicatedFrom(source);
        List<String> warnings = new ArrayList<>();
        apply(copy, request, warnings);
        return toResponse(quotationRepository.save(copy), warnings);
    }

    /**
     * DRAFT -> ISSUED: refreshes the customer and business-profile snapshots one last time (rates stay as saved),
     * recalculates GST for the final place of supply and assigns the next configured quotation number.
     */
    @Transactional
    public WholesaleQuotationResponse issue(Integer id, String username) {
        WholesaleQuotation quotation = find(id);
        requireStatus(quotation, WholesaleQuotationStatus.DRAFT, "issued");
        LocalDate today = WholesaleDocumentNumberService.today();
        if (quotation.getValidUntil().isBefore(today)) {
            throw WholesaleException.badRequest("The validity date " + quotation.getValidUntil()
                + " has already passed. Edit the draft and set a new validity date before issuing.");
        }
        WholesaleCustomer customer = activeCustomer(quotation.getCustomer().getWholesaleCustomerId());
        List<String> warnings = new ArrayList<>();
        snapshotParties(quotation, customer, warnings);
        recalculate(quotation);

        quotation.setQuotationNumber(numberService.next(WholesaleDocumentSettings.QUOTATION,
            quotation.getQuotationDate(), quotationRepository::existsByQuotationNumber));
        quotation.setStatus(WholesaleQuotationStatus.ISSUED);
        LocalDateTime now = LocalDateTime.now();
        quotation.setIssuedAt(now);
        quotation.setIssuedBy(username);
        quotation.setStatusChangedAt(now);
        quotation.setStatusChangedBy(username);
        return toResponse(quotationRepository.save(quotation), warnings);
    }

    /** ACCEPTED / REJECTED / CANCELLED, following {@link WholesaleQuotationStatus#allowedNext()}. */
    @Transactional
    public WholesaleQuotationResponse changeStatus(Integer id, WholesaleQuotationStatusRequest request, String username) {
        expireOverdue();
        WholesaleQuotation quotation = find(id);
        WholesaleQuotationStatus target = WholesaleQuotationStatus.valueOf(request.status());
        if (!quotation.getStatus().canMoveTo(target)) {
            throw WholesaleException.conflict("A " + quotation.getStatus().name().toLowerCase(Locale.ROOT)
                + " quotation cannot be marked " + target.name().toLowerCase(Locale.ROOT));
        }
        quotation.setStatus(target);
        quotation.setStatusChangedAt(LocalDateTime.now());
        quotation.setStatusChangedBy(username);
        if (target == WholesaleQuotationStatus.CANCELLED) {
            quotation.setCancelReason(WholesaleProductService.blankToNull(request.reason()));
        }
        return toResponse(quotationRepository.save(quotation), List.of());
    }

    /** ISSUED quotations whose validity date has passed become EXPIRED (run before reads and status changes). */
    @Transactional
    public int expireOverdue() {
        return quotationRepository.expireOverdue(WholesaleDocumentNumberService.today(), LocalDateTime.now());
    }

    // ── Building ─────────────────────────────────────────────

    private void apply(WholesaleQuotation q, WholesaleQuotationRequest r, List<String> warnings) {
        WholesaleDocumentSettings settings = numberService.settings(WholesaleDocumentSettings.QUOTATION);
        LocalDate date = r.quotationDate();
        LocalDate validUntil = r.validUntil() != null ? r.validUntil() : date.plusDays(settings.getDefaultValidityDays());
        if (validUntil.isBefore(date)) throw WholesaleException.badRequest("Validity date cannot be before the quotation date");

        q.setQuotationDate(date);
        q.setValidUntil(validUntil);
        q.setNotes(WholesaleProductService.blankToNull(r.notes()));
        String terms = WholesaleProductService.blankToNull(r.terms());
        q.setTerms(terms != null ? terms : settings.getDefaultTerms());
        q.setOtherChargesLabel(WholesaleProductService.blankToNull(r.otherChargesLabel()));
        q.setOtherChargesAmount(r.otherChargesAmount() != null ? r.otherChargesAmount() : BigDecimal.ZERO);
        q.setOtherChargesGstPct(r.otherChargesGstPct() != null ? r.otherChargesGstPct() : BigDecimal.ZERO);
        if (q.getOtherChargesAmount().signum() > 0 && q.getOtherChargesLabel() == null) q.setOtherChargesLabel("Other charges");

        snapshotParties(q, activeCustomer(r.customerId()), warnings);

        q.getItems().clear();
        if (q.getQuotationId() != null) {
            // Delete the old lines before inserting new ones; Hibernate would otherwise insert first and
            // collide with the unique (quotation_id, line_no) constraint.
            quotationRepository.flush();
        }
        int lineNo = 1;
        for (WholesaleQuotationItemRequest line : r.items()) {
            q.getItems().add(buildItem(q, line, lineNo++, date, warnings));
        }
        recalculate(q);
    }

    private WholesaleQuotationItem buildItem(WholesaleQuotation q, WholesaleQuotationItemRequest line, int lineNo,
                                             LocalDate priceDate, List<String> warnings) {
        WholesaleLinePricingService.PricedLine priced = linePricing.price(line.wholesaleProductId(), line.quantity(),
            line.rate(), line.gstPct(), priceDate, lineNo, warnings, "quotations do not reserve stock");
        WholesaleProduct product = priced.product();
        WholesaleProductPriceRule rule = priced.rule();

        WholesaleQuotationItem item = new WholesaleQuotationItem();
        item.setQuotation(q);
        item.setLineNo(lineNo);
        item.setProduct(product);
        item.setPriceRule(rule);
        item.setRuleRate(priced.ruleRate());
        item.setRateSource(priced.rateSource());
        item.setItemName(product.getProductName());
        item.setProductCode(product.getProductCode());
        item.setHsnCode(product.getHsnCode());
        item.setUnit(product.getUnit());
        item.setDescription(WholesaleProductService.blankToNull(line.description()));
        item.setQuantity(line.quantity());
        item.setRate(priced.rate());
        item.setDiscountPct(line.discountPct() != null ? line.discountPct() : BigDecimal.ZERO);
        item.setGstPct(priced.gstPct());
        return item;
    }

    /** Copies customer + business profile onto the quotation and decides intra/inter-state. */
    private void snapshotParties(WholesaleQuotation q, WholesaleCustomer c, List<String> warnings) {
        WholesaleDocumentProfile firm = profileService.documentProfile(); // 409 if the profile is incomplete
        try {
            q.setFirmSnapshotJson(objectMapper.writeValueAsString(firm));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store business profile snapshot", e);
        }
        q.setFirmStateCode(firm.stateCode());

        q.setCustomer(c);
        q.setCustomerName(c.getCustomerName());
        q.setCustomerBusinessName(c.getBusinessName());
        q.setCustomerGstNumber(c.getGstNumber());
        q.setCustomerPhone(c.getPhone());
        q.setCustomerEmail(c.getEmail());
        q.setBillingAddress(c.getBillingAddress());
        q.setBillingCity(c.getCity());
        q.setBillingStateCode(c.getStateCode());
        q.setBillingStateName(c.getStateName());
        q.setBillingPinCode(c.getPinCode());
        q.setShippingAddress(c.getShippingAddress());
        q.setShippingCity(c.getShippingCity());
        q.setShippingStateCode(c.getShippingStateCode());
        q.setShippingStateName(c.getShippingStateName());
        q.setShippingPinCode(c.getShippingPinCode());

        // Place of supply for goods = delivery (shipping) state, falling back to the billing state.
        String place = c.getShippingStateCode() != null ? c.getShippingStateCode() : c.getStateCode();
        q.setPlaceOfSupplyStateCode(place);
        q.setInterstate(place != null && !place.equals(firm.stateCode()));
        if (place == null) {
            warnings.add("Customer has no state, so GST is calculated as intra-state (CGST + SGST). Add the customer's state to be sure.");
        }
    }

    /** Recomputes every amount from the stored rates (never re-prices). */
    private void recalculate(WholesaleQuotation q) {
        boolean interstate = Boolean.TRUE.equals(q.getInterstate());
        List<WholesaleQuotationCalculator.LineInput> inputs = q.getItems().stream()
            .map(i -> new WholesaleQuotationCalculator.LineInput(i.getQuantity(), i.getRate(), i.getDiscountPct(), i.getGstPct()))
            .toList();
        WholesaleQuotationCalculator.Totals t = WholesaleQuotationCalculator.totals(inputs, interstate,
            q.getOtherChargesAmount(), q.getOtherChargesGstPct());
        for (int i = 0; i < q.getItems().size(); i++) {
            WholesaleQuotationItem item = q.getItems().get(i);
            WholesaleQuotationCalculator.LineResult r = t.lines().get(i);
            item.setGrossAmount(r.gross());
            item.setDiscountAmount(r.discount());
            item.setTaxableAmount(r.taxable());
            item.setCgstAmount(r.cgst());
            item.setSgstAmount(r.sgst());
            item.setIgstAmount(r.igst());
            item.setGstAmount(r.gst());
            item.setTotalAmount(r.total());
        }
        q.setGrossAmount(t.gross());
        q.setDiscountAmount(t.discount());
        q.setSubtotal(t.subtotal());
        q.setOtherChargesAmount(t.otherCharges());
        q.setOtherChargesGstAmount(t.otherChargesGst());
        q.setCgstAmount(t.cgst());
        q.setSgstAmount(t.sgst());
        q.setIgstAmount(t.igst());
        q.setGstAmount(t.gst());
        q.setRoundOff(t.roundOff());
        q.setGrandTotal(t.grandTotal());
    }

    // ── Helpers ──────────────────────────────────────────────

    private WholesaleCustomer activeCustomer(Integer customerId) {
        WholesaleCustomer c = customerRepository.findById(customerId)
            .orElseThrow(() -> WholesaleException.badRequest("Wholesale customer #" + customerId + " not found"));
        if (!Boolean.TRUE.equals(c.getIsActive())) {
            throw WholesaleException.badRequest("Customer '" + c.getCustomerName() + "' is inactive");
        }
        return c;
    }

    private WholesaleQuotation find(Integer id) {
        return quotationRepository.findByIdWithItems(id)
            .orElseThrow(() -> WholesaleException.notFound("Quotation #" + id + " not found"));
    }

    private static void requireStatus(WholesaleQuotation q, WholesaleQuotationStatus required, String action) {
        if (q.getStatus() != required) {
            throw WholesaleException.conflict("Only " + required.name().toLowerCase(Locale.ROOT) + " quotations can be "
                + action + "; this one is " + q.getStatus().name().toLowerCase(Locale.ROOT)
                + (action.equals("edited") ? ". Duplicate it to make changes." : "."));
        }
    }

    private static List<WholesaleQuotationStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) return List.of(WholesaleQuotationStatus.values());
        List<WholesaleQuotationStatus> out = new ArrayList<>();
        for (String s : status.split(",")) {
            try {
                out.add(WholesaleQuotationStatus.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw WholesaleException.badRequest("Unknown quotation status " + s.trim());
            }
        }
        return out;
    }

    static String displayNumber(WholesaleQuotation q) {
        if (q.getQuotationNumber() != null) return q.getQuotationNumber();
        return q.getQuotationId() != null ? "Draft #" + q.getQuotationId() : "Draft";
    }

    static List<String> allowedActions(WholesaleQuotationStatus status) {
        List<String> actions = new ArrayList<>();
        if (status.isEditable()) actions.addAll(List.of("EDIT", "DELETE"));
        if (status.canMoveTo(WholesaleQuotationStatus.ISSUED)) actions.add("ISSUE");
        if (status.canMoveTo(WholesaleQuotationStatus.ACCEPTED)) actions.add("ACCEPT");
        if (status.canMoveTo(WholesaleQuotationStatus.REJECTED)) actions.add("REJECT");
        if (status.canMoveTo(WholesaleQuotationStatus.CANCELLED)) actions.add("CANCEL");
        // CONVERTED quotations can be converted again only with an explicit allowDuplicate (UI asks first).
        if (status.isConvertible() || status == WholesaleQuotationStatus.CONVERTED) actions.add("CONVERT");
        actions.addAll(List.of("DUPLICATE", "PRINT", "PDF", "SHARE"));
        return actions;
    }

    WholesaleQuotationResponse toResponse(WholesaleQuotation q, List<String> warnings) {
        List<WholesaleQuotationConversion> conversions = conversions(q.getQuotationId());
        WholesaleDocumentProfile firm;
        try {
            firm = objectMapper.readValue(q.getFirmSnapshotJson(), WholesaleDocumentProfile.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored business profile snapshot is unreadable", e);
        }
        WholesaleQuotationResponse.Customer customer = new WholesaleQuotationResponse.Customer(
            q.getCustomer().getWholesaleCustomerId(), q.getCustomerName(), q.getCustomerBusinessName(),
            q.getCustomerGstNumber(), q.getCustomerPhone(), q.getCustomerEmail(), q.getBillingAddress(), q.getBillingCity(),
            q.getBillingStateCode(), q.getBillingStateName(), q.getBillingPinCode(), q.getShippingAddress(),
            q.getShippingCity(), q.getShippingStateCode(), q.getShippingStateName(), q.getShippingPinCode());
        boolean editable = q.getStatus().isEditable();
        List<WholesaleQuotationResponse.Item> items = q.getItems().stream().map(i -> new WholesaleQuotationResponse.Item(
            i.getQuotationItemId(), i.getLineNo(), i.getProduct().getWholesaleProductId(), i.getItemName(), i.getProductCode(),
            i.getHsnCode(), i.getUnit(), i.getDescription(), i.getQuantity(), i.getRate(), i.getRateSource(), i.getRuleRate(),
            i.getPriceRule() != null ? i.getPriceRule().getPriceRuleId() : null, i.getDiscountPct(), i.getGrossAmount(),
            i.getDiscountAmount(), i.getTaxableAmount(), i.getGstPct(), i.getCgstAmount(), i.getSgstAmount(),
            i.getIgstAmount(), i.getGstAmount(), i.getTotalAmount(),
            editable ? i.getProduct().getAvailableQuantity() : null)).toList();
        WholesaleQuotationResponse.Totals totals = new WholesaleQuotationResponse.Totals(q.getGrossAmount(),
            q.getDiscountAmount(), q.getSubtotal(), q.getOtherChargesLabel(), q.getOtherChargesAmount(),
            q.getOtherChargesGstPct(), q.getOtherChargesGstAmount(), q.getCgstAmount(), q.getSgstAmount(),
            q.getIgstAmount(), q.getGstAmount(), q.getRoundOff(), q.getGrandTotal());
        return new WholesaleQuotationResponse(q.getQuotationId(), q.getQuotationNumber(), displayNumber(q),
            q.getStatus().name(), q.getQuotationDate(), q.getValidUntil(), firm, customer, q.getPlaceOfSupplyStateCode(),
            q.getInterstate(), items, totals, q.getNotes(), q.getTerms(),
            q.getDuplicatedFrom() != null ? q.getDuplicatedFrom().getQuotationId() : null, q.getCancelReason(),
            q.getCreatedAt(), q.getCreatedBy(), q.getUpdatedAt(), q.getIssuedAt(), q.getIssuedBy(),
            q.getStatusChangedAt(), q.getStatusChangedBy(), allowedActions(q.getStatus()), warnings,
            conversionStatus(conversions), conversions);
    }

    /** Sales Receipts / Credit Notes created from this quotation, oldest first. */
    List<WholesaleQuotationConversion> conversions(Integer quotationId) {
        if (quotationId == null) return List.of();
        return salesDocumentRepository.findBySourceQuotation_QuotationIdOrderByCreatedAtAsc(quotationId).stream()
            .map(d -> new WholesaleQuotationConversion(d.getSalesDocumentId(), d.getDocType().name(), d.getDocType().getLabel(),
                d.getDocumentNumber(), d.getDocumentDate(), d.getStatus().name(), d.getGrandTotal(), d.getConvertedAt(),
                d.getConvertedBy()))
            .toList();
    }

    private static String conversionStatus(List<WholesaleQuotationConversion> conversions) {
        if (conversions.isEmpty()) return "NOT_CONVERTED";
        return conversions.stream().anyMatch(c -> WholesaleSalesDocumentStatus.valueOf(c.status()).isActive())
            ? "CONVERTED" : "CONVERSION_CANCELLED";
    }
}
