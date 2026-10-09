package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.WholesaleCustomerMatch;
import com.example.mybill.wholesale.dto.WholesaleCustomerOptions;
import com.example.mybill.wholesale.dto.WholesaleCustomerRequest;
import com.example.mybill.wholesale.dto.WholesaleCustomerResponse;
import com.example.mybill.wholesale.entity.WholesaleCustomer;
import com.example.mybill.wholesale.entity.WholesaleCustomerType;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleCustomerRepository;
import com.example.mybill.wholesale.repository.WholesaleQuotationRepository;
import com.example.mybill.wholesale.repository.WholesaleSalesDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Wholesale customers (B2B parties). Never reads or writes the retail `customers` table.
 *
 * Duplicate rules: a GSTIN can belong to only one wholesale customer (hard rule, 409);
 * the same phone number or customer name is only reported as a warning via {@link #findDuplicates}.
 */
@Service
public class WholesaleCustomerService {

    private static final int MAX_LIMIT = 500;

    @Autowired private WholesaleCustomerRepository customerRepository;
    @Autowired private WholesaleQuotationRepository quotationRepository;
    @Autowired private WholesaleSalesDocumentRepository salesDocumentRepository;

    @Transactional(readOnly = true)
    public List<WholesaleCustomerResponse> search(String q, String type, boolean activeOnly, Integer limit) {
        WholesaleCustomerType parsedType = parseType(type);
        List<WholesaleCustomerType> types = parsedType != null ? List.of(parsedType) : List.of(WholesaleCustomerType.values());
        List<Boolean> statuses = activeOnly ? List.of(true) : List.of(true, false);
        String term = q == null ? "" : q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
        int size = limit == null || limit <= 0 ? MAX_LIMIT : Math.min(limit, MAX_LIMIT);
        return customerRepository.search(statuses, types, like, like.toUpperCase(Locale.ROOT), PageRequest.of(0, size)).stream()
            .map(WholesaleCustomerService::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public WholesaleCustomerResponse get(Integer id) {
        return toResponse(find(id));
    }

    @Transactional
    public WholesaleCustomerResponse create(WholesaleCustomerRequest request) {
        WholesaleCustomer customer = new WholesaleCustomer();
        apply(customer, request);
        assertGstAvailable(customer.getGstNumber(), null);
        return toResponse(customerRepository.save(customer));
    }

    @Transactional
    public WholesaleCustomerResponse update(Integer id, WholesaleCustomerRequest request) {
        WholesaleCustomer customer = find(id);
        apply(customer, request);
        assertGstAvailable(customer.getGstNumber(), id);
        return toResponse(customerRepository.save(customer));
    }

    /**
     * Hard delete, only for customers not used on any wholesale document (FK-protected as well).
     * Customers with documents should be marked inactive instead.
     */
    @Transactional
    public void delete(Integer id) {
        WholesaleCustomer customer = find(id);
        if (quotationRepository.existsByCustomer_WholesaleCustomerId(id)
                || salesDocumentRepository.existsByCustomer_WholesaleCustomerId(id)) {
            throw WholesaleException.conflict("'" + customer.getCustomerName()
                + "' has wholesale quotations or sales documents and cannot be deleted. Mark the customer inactive instead.");
        }
        customerRepository.delete(customer);
    }

    /** Possible duplicates of the given details (excluding excludeId, e.g. the customer being edited). */
    @Transactional(readOnly = true)
    public List<WholesaleCustomerMatch> findDuplicates(String gstNumber, String phone, String customerName, Integer excludeId) {
        Map<Integer, WholesaleCustomer> found = new LinkedHashMap<>();
        Map<Integer, List<String>> reasons = new HashMap<>();
        Set<Integer> blocking = new HashSet<>();

        String gst = normalizeGst(gstNumber);
        if (gst != null) {
            customerRepository.findByGstNumber(gst).ifPresent(c -> {
                note(found, reasons, c, "Same GST number");
                blocking.add(c.getWholesaleCustomerId());
            });
        }
        String last10 = lastTenDigits(phone);
        if (last10 != null) {
            customerRepository.findByPhoneEndingWith(last10).forEach(c -> note(found, reasons, c, "Same phone number"));
        }
        String name = WholesaleProductService.blankToNull(customerName);
        if (name != null) {
            customerRepository.findByCustomerNameIgnoreCase(name).forEach(c -> note(found, reasons, c, "Same customer name"));
        }
        if (excludeId != null) found.remove(excludeId);

        return found.values().stream().map(c -> new WholesaleCustomerMatch(
            c.getWholesaleCustomerId(), c.getCustomerName(), c.getBusinessName(), c.getGstNumber(), c.getPhone(),
            c.getCity(), c.getIsActive(), reasons.get(c.getWholesaleCustomerId()),
            blocking.contains(c.getWholesaleCustomerId()))).toList();
    }

    public WholesaleCustomerOptions options() {
        List<WholesaleCustomerOptions.Option> types = Arrays.stream(WholesaleCustomerType.values())
            .map(t -> new WholesaleCustomerOptions.Option(t.name(), t.getLabel())).toList();
        List<WholesaleCustomerOptions.Option> states = GstStates.all().entrySet().stream()
            .map(e -> new WholesaleCustomerOptions.Option(e.getKey(), e.getValue())).toList();
        return new WholesaleCustomerOptions(types, states);
    }

    private void apply(WholesaleCustomer c, WholesaleCustomerRequest r) {
        String gst = normalizeGst(r.gstNumber());
        String stateCode = WholesaleProductService.blankToNull(r.stateCode());
        if (stateCode != null && !GstStates.isValid(stateCode)) {
            throw WholesaleException.badRequest("Unknown state code " + stateCode);
        }
        if (gst != null) {
            String gstState = gst.substring(0, 2);
            if (!GstStates.isValid(gstState)) {
                throw WholesaleException.badRequest("GST number starts with " + gstState + ", which is not a GST state code");
            }
            if (stateCode == null) {
                stateCode = gstState;
            } else if (!stateCode.equals(gstState)) {
                throw WholesaleException.badRequest("GST number is registered in " + GstStates.name(gstState) + " (" + gstState
                    + ") but the billing state is " + GstStates.name(stateCode) + " (" + stateCode + ")");
            }
        }

        c.setCustomerName(r.customerName().trim());
        c.setBusinessName(WholesaleProductService.blankToNull(r.businessName()));
        WholesaleCustomerType type = parseType(r.customerType());
        c.setCustomerType(type != null ? type : WholesaleCustomerType.DEFAULT);
        c.setGstNumber(gst);
        c.setPhone(normalizePhone(r.phone()));
        c.setEmail(blankToLower(r.email()));
        c.setBillingAddress(WholesaleProductService.blankToNull(r.billingAddress()));
        c.setCity(WholesaleProductService.blankToNull(r.city()));
        c.setStateCode(stateCode);
        c.setStateName(stateCode != null ? GstStates.name(stateCode) : null);
        c.setPinCode(WholesaleProductService.blankToNull(r.pinCode()));
        c.setNotes(WholesaleProductService.blankToNull(r.notes()));
        if (r.isActive() != null) c.setIsActive(r.isActive());

        boolean same = r.shippingSameAsBilling() == null || r.shippingSameAsBilling();
        c.setShippingSameAsBilling(same);
        if (same) {
            c.setShippingAddress(c.getBillingAddress());
            c.setShippingCity(c.getCity());
            c.setShippingStateCode(c.getStateCode());
            c.setShippingStateName(c.getStateName());
            c.setShippingPinCode(c.getPinCode());
        } else {
            String shipState = WholesaleProductService.blankToNull(r.shippingStateCode());
            if (shipState != null && !GstStates.isValid(shipState)) {
                throw WholesaleException.badRequest("Unknown shipping state code " + shipState);
            }
            c.setShippingAddress(WholesaleProductService.blankToNull(r.shippingAddress()));
            c.setShippingCity(WholesaleProductService.blankToNull(r.shippingCity()));
            c.setShippingStateCode(shipState);
            c.setShippingStateName(shipState != null ? GstStates.name(shipState) : null);
            c.setShippingPinCode(WholesaleProductService.blankToNull(r.shippingPinCode()));
        }
    }

    private void assertGstAvailable(String gst, Integer exceptId) {
        if (gst == null) return;
        customerRepository.findByGstNumber(gst)
            .filter(other -> !other.getWholesaleCustomerId().equals(exceptId))
            .ifPresent(other -> {
                throw WholesaleException.conflict("GST number " + gst + " is already registered for '"
                    + other.getCustomerName() + "'" + (Boolean.TRUE.equals(other.getIsActive()) ? "" : " (inactive — reactivate it instead)"));
            });
    }

    private static void note(Map<Integer, WholesaleCustomer> found, Map<Integer, List<String>> reasons,
                             WholesaleCustomer c, String reason) {
        found.putIfAbsent(c.getWholesaleCustomerId(), c);
        reasons.computeIfAbsent(c.getWholesaleCustomerId(), k -> new ArrayList<>()).add(reason);
    }

    private static WholesaleCustomerType parseType(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return WholesaleCustomerType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw WholesaleException.badRequest("Unknown customer type " + type);
        }
    }

    static String normalizeGst(String gst) {
        String v = WholesaleProductService.blankToNull(gst);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    /** Keeps digits and a leading '+', e.g. "+91 98765-43210" -> "+919876543210". */
    static String normalizePhone(String phone) {
        String v = WholesaleProductService.blankToNull(phone);
        if (v == null) return null;
        String digits = v.replaceAll("[^0-9]", "");
        if (digits.length() < 10 || digits.length() > 13) {
            throw WholesaleException.badRequest("Phone number must contain 10 to 13 digits");
        }
        return (v.startsWith("+") ? "+" : "") + digits;
    }

    private static String lastTenDigits(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : null;
    }

    private static String blankToLower(String s) {
        String v = WholesaleProductService.blankToNull(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private WholesaleCustomer find(Integer id) {
        return customerRepository.findById(id)
            .orElseThrow(() -> WholesaleException.notFound("Wholesale customer #" + id + " not found"));
    }

    static WholesaleCustomerResponse toResponse(WholesaleCustomer c) {
        return new WholesaleCustomerResponse(
            c.getWholesaleCustomerId(), c.getCustomerName(), c.getBusinessName(), c.getCustomerType().name(),
            c.getCustomerType().getLabel(), c.getGstNumber(), c.getPhone(), c.getEmail(), c.getBillingAddress(),
            c.getCity(), c.getStateCode(), c.getStateName(), c.getPinCode(), c.getShippingSameAsBilling(),
            c.getShippingAddress(), c.getShippingCity(), c.getShippingStateCode(), c.getShippingStateName(),
            c.getShippingPinCode(), c.getNotes(), c.getIsActive(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
