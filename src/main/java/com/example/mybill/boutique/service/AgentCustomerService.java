package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.CustomerResponse;
import com.example.mybill.boutique.dto.CustomerUpsertRequest;
import com.example.mybill.boutique.dto.MeasurementRequest;
import com.example.mybill.boutique.dto.MeasurementResponse;
import com.example.mybill.boutique.entity.BoutiqueCustomer;
import com.example.mybill.boutique.entity.BoutiqueMeasurement;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.BoutiqueCustomerRepository;
import com.example.mybill.boutique.repository.BoutiqueMeasurementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Customer identity (phone / Instagram) and size profiles for the agent. */
@Service
public class AgentCustomerService {

    public static final String MEASUREMENT_SOURCE = "AGENT";

    @Autowired private BoutiqueCustomerRepository customerRepository;
    @Autowired private BoutiqueMeasurementRepository measurementRepository;
    @Autowired private AgentCatalogService catalogService;
    @Autowired private CustomerMergeService mergeService;

    public record UpsertResult(CustomerResponse customer, boolean created) {}

    /**
     * Find by phone, else by Instagram id, else (for Indian numbers) an existing shop customer whose
     * free-text phone matches; otherwise create. Only fills fields that are empty — never overwrites.
     * When the phone and the Instagram id belong to two different customers, they are merged first
     * (CustomerMergeService: the older one is kept).
     */
    @Transactional
    public UpsertResult upsert(CustomerUpsertRequest request) {
        String phone = blankToNull(request.phoneE164());
        String igId = blankToNull(request.instagramUserId());
        String name = blankToNull(request.name());

        Integer mergedFrom = null;
        if (phone != null && igId != null) {
            Optional<BoutiqueCustomer> byPhone = customerRepository.findByPhoneE164(phone);
            Optional<BoutiqueCustomer> byIg = customerRepository.findByInstagramUserId(igId);
            if (byPhone.isPresent() && byIg.isPresent()
                    && !byPhone.get().getCustomerId().equals(byIg.get().getCustomerId())) {
                mergedFrom = mergeService.merge(byPhone.get().getCustomerId(), byIg.get().getCustomerId(), phone, igId)
                    .mergedCustomerId();
            }
        }

        Optional<BoutiqueCustomer> match = Optional.empty();
        if (phone != null) match = customerRepository.findByPhoneE164(phone);
        if (match.isEmpty() && igId != null) match = customerRepository.findByInstagramUserId(igId);
        if (match.isEmpty() && phone != null && phone.startsWith("+91") && phone.length() == 13) {
            // Shop customers created before phone_e164 existed. Newest first if several share the number.
            List<BoutiqueCustomer> legacy = customerRepository.findUnlinkedByNationalPhone(phone.substring(3));
            if (!legacy.isEmpty()) match = Optional.of(legacy.get(0));
        }

        boolean created = match.isEmpty();
        BoutiqueCustomer customer = match.orElseGet(() -> newCustomer(phone, igId));

        if (phone != null && customer.getPhoneE164() == null) {
            ensureUnused(customerRepository.findByPhoneE164(phone), customer, "PHONE_IN_USE",
                "Another customer already has this phone number");
            customer.setPhoneE164(phone);
        }
        if (igId != null && customer.getInstagramUserId() == null) {
            ensureUnused(customerRepository.findByInstagramUserId(igId), customer, "INSTAGRAM_IN_USE",
                "Another customer already has this Instagram account");
            customer.setInstagramUserId(igId);
        }
        if (name != null && blankToNull(customer.getCustomerName()) == null) {
            customer.setCustomerName(name.length() > 100 ? name.substring(0, 100) : name);
        }
        customer.setUpdatedAt(LocalDateTime.now());
        return new UpsertResult(toResponse(customerRepository.save(customer), created, mergedFrom), created);
    }

    /** Records her answer to "may we send you order updates and offers on WhatsApp?" (latest answer wins). */
    @Transactional
    public CustomerResponse setConsent(Integer customerId, boolean whatsappUpdates) {
        BoutiqueCustomer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> AgentApiException.notFound("CUSTOMER_NOT_FOUND", "No customer with id " + customerId));
        LocalDateTime now = LocalDateTime.now();
        customer.setWhatsappUpdatesConsent(whatsappUpdates);
        customer.setConsentAt(now);
        customer.setUpdatedAt(now);
        return toResponse(customerRepository.save(customer), false, null);
    }

    @Transactional(readOnly = true)
    public MeasurementResponse latestMeasurement(Integer customerId) {
        requireCustomer(customerId);
        return measurementRepository.findFirstByCustomerIdOrderByCreatedAtDescMeasurementIdDesc(customerId)
            .map(this::toResponse)
            .orElseThrow(() -> AgentApiException.notFound("MEASUREMENTS_NOT_FOUND",
                "No measurements recorded for customer " + customerId));
    }

    /** Always inserts a new row (history is kept); the newest row is the current profile. */
    @Transactional
    public MeasurementResponse saveMeasurement(Integer customerId, MeasurementRequest request) {
        requireCustomer(customerId);
        BoutiqueMeasurement m = new BoutiqueMeasurement();
        m.setCustomerId(customerId);
        m.setProfileName("Online");
        m.setSizeCode(catalogService.requireSize(request.sizeCode()));
        m.setHeightCm(request.heightCm());
        m.setBustIn(request.bustIn());
        m.setWaistIn(request.waistIn());
        m.setHipIn(request.hipIn());
        m.setFitPreference(request.fitPreference() == null ? null : request.fitPreference().toUpperCase(Locale.ROOT));
        m.setNotes(blankToNull(request.notes()));
        m.setSource(MEASUREMENT_SOURCE);
        m.setCreatedAt(LocalDateTime.now());
        return toResponse(measurementRepository.save(m));
    }

    /** Latest measurements as entity (for order snapshots), or empty. */
    @Transactional(readOnly = true)
    public Optional<BoutiqueMeasurement> findLatestMeasurement(Integer customerId) {
        return measurementRepository.findFirstByCustomerIdOrderByCreatedAtDescMeasurementIdDesc(customerId);
    }

    @Transactional(readOnly = true)
    public boolean exists(Integer customerId) {
        return customerRepository.existsById(customerId);
    }

    private void requireCustomer(Integer customerId) {
        if (!customerRepository.existsById(customerId)) {
            throw AgentApiException.notFound("CUSTOMER_NOT_FOUND", "No customer with id " + customerId);
        }
    }

    private static void ensureUnused(Optional<BoutiqueCustomer> owner, BoutiqueCustomer customer, String code, String message) {
        if (owner.isPresent() && !owner.get().getCustomerId().equals(customer.getCustomerId())) {
            throw AgentApiException.conflict(code, message);
        }
    }

    private static BoutiqueCustomer newCustomer(String phone, String igId) {
        BoutiqueCustomer c = new BoutiqueCustomer();
        c.setSource(phone != null ? "WHATSAPP" : "INSTAGRAM");
        // The retail UI shows the legacy phone column: use the 10-digit national number for Indian mobiles.
        if (phone != null) c.setPhone(phone.startsWith("+91") && phone.length() == 13 ? phone.substring(3)
            : (phone.length() <= 15 ? phone : null));
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    private CustomerResponse toResponse(BoutiqueCustomer c, boolean created, Integer mergedFrom) {
        return new CustomerResponse(c.getCustomerId(), c.getCustomerName(), c.getPhoneE164(), c.getInstagramUserId(),
            c.getSource(), created, mergedFrom, c.getWhatsappUpdatesConsent(), c.getConsentAt(),
            c.getCreatedAt(), c.getUpdatedAt());
    }

    private MeasurementResponse toResponse(BoutiqueMeasurement m) {
        return new MeasurementResponse(m.getMeasurementId(), m.getCustomerId(), m.getSizeCode(), m.getHeightCm(),
            m.getBustIn(), m.getWaistIn(), m.getHipIn(), m.getFitPreference(), m.getNotes(), m.getSource(), m.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
