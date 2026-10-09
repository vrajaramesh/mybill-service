package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.*;
import com.example.mybill.wholesale.entity.*;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleCustomerRepository;
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
 * Wholesale Sales Receipts, Credit Notes and Debit Notes.
 *
 * What each document type MEANS (label, printed title, GST class, CHARGE/CREDIT value effect, OUT/IN/NONE stock effect,
 * reference and reason rules) comes from wholesale_document_settings and is COPIED onto the document; issued documents
 * are always processed with their own copy, so configuration (or GST rule) changes never reinterpret history.
 *
 * <ul>
 *   <li><b>From a quotation</b> ({@link #convert}): everything is copied from the quotation, never re-priced.</li>
 *   <li><b>Direct Sales Receipt</b> ({@link #createReceipt}): DRAFT priced from the quantity slabs on the receipt date;
 *       the rate used is stored per line and never changed afterwards (editing a draft sends the stored rates back).</li>
 *   <li><b>Issue</b>: snapshots frozen, stock deducted (all-or-nothing), number assigned, optional first payment.</li>
 *   <li><b>Payments</b>: ISSUED → PARTIALLY_PAID → PAID from recorded payments; payments are voided, never deleted.</li>
 *   <li><b>Cancel</b>: stock restored for issued documents; a converted quotation goes back to ACCEPTED when no
 *       active document remains.</li>
 * </ul>
 * Every write locks the document (and quotation) row and runs in one transaction. Retail bills are never involved.
 */
@Service
public class WholesaleSalesDocumentService {

    private static final int MAX_LIST = 500;
    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2999, 12, 31);
    private static final List<WholesaleSalesDocumentStatus> ACTIVE = List.of(
        WholesaleSalesDocumentStatus.ISSUED, WholesaleSalesDocumentStatus.PARTIALLY_PAID, WholesaleSalesDocumentStatus.PAID);

    @Autowired private WholesaleSalesDocumentRepository documentRepository;
    @Autowired private WholesaleQuotationRepository quotationRepository;
    @Autowired private WholesaleCustomerRepository customerRepository;
    @Autowired private WholesaleQuotationService quotationService;
    @Autowired private WholesaleBusinessProfileService profileService;
    @Autowired private WholesaleDocumentNumberService numberService;
    @Autowired private WholesaleLinePricingService linePricing;
    @Autowired private WholesaleInventoryService inventoryService;
    @Autowired private ObjectMapper objectMapper;

    // ── Conversion from a quotation ──────────────────────────

    @Transactional
    public WholesaleSalesDocumentResponse convert(Integer quotationId, WholesaleConversionRequest r, String username) {
        quotationService.expireOverdue();
        WholesaleQuotation q = quotationRepository.findByIdForUpdate(quotationId)
            .orElseThrow(() -> WholesaleException.notFound("Quotation #" + quotationId + " not found"));
        WholesaleSalesDocumentType type = WholesaleSalesDocumentType.valueOf(r.convertTo());

        assertConvertible(q, Boolean.TRUE.equals(r.allowDuplicate()));

        LocalDate today = WholesaleDocumentNumberService.today();
        LocalDate documentDate = r.documentDate() != null ? r.documentDate() : today;
        if (documentDate.isAfter(today)) throw WholesaleException.badRequest("Document date cannot be in the future");
        if (documentDate.isBefore(q.getQuotationDate())) {
            throw WholesaleException.badRequest("Document date cannot be before the quotation date " + q.getQuotationDate());
        }

        // Letterhead / bank / UPI from the current profile; the copied GST split is only valid in the same firm state.
        WholesaleDocumentProfile firm = profileService.documentProfile();
        if (!firm.stateCode().equals(q.getFirmStateCode())) {
            throw WholesaleException.conflict("The firm's state has changed since this quotation was issued, so its GST "
                + "(CGST/SGST vs IGST) no longer applies. Duplicate the quotation and issue it again before converting.");
        }

        WholesaleDocumentSettings settings = numberService.settings(type.name());
        if (settings.getValueEffect() == WholesaleValueEffect.CREDIT) {
            throw WholesaleException.conflict(settings.getDisplayLabel() + " is configured as a credit document and cannot be "
                + "created from a quotation");
        }
        WholesaleSalesDocument d = new WholesaleSalesDocument();
        d.setDocType(type);
        d.setDocumentDate(documentDate);
        d.setSourceQuotation(q);
        applySemantics(d, settings);
        d.setReferenceDocType("QUOTATION");
        d.setReferenceNumber(q.getQuotationNumber());
        d.setReferenceDate(q.getQuotationDate());
        copyParties(q, d);
        d.setFirmSnapshotJson(toJson(firm));
        copyTotals(q, d);
        copyItems(q, d);
        d.setNotes(r.notes() != null && !r.notes().isBlank() ? r.notes().trim() : q.getNotes());
        d.setTerms(settings.getDefaultTerms() != null ? settings.getDefaultTerms() : q.getTerms());
        d.setAmountPaid(BigDecimal.ZERO);
        d.setPaymentStatus("UNPAID");

        applyDueDate(d, r.dueDate(), settings);

        LocalDateTime now = LocalDateTime.now();
        audit(d, "CREATED_FROM_QUOTATION", username, "Converted from quotation " + q.getQuotationNumber()
            + "; quoted rates and totals copied");
        issueInto(d, username, now);
        d.setConvertedBy(username);
        d.setConvertedAt(now);

        if (type == WholesaleSalesDocumentType.SALES_RECEIPT) {
            BigDecimal received = r.amountReceived() != null ? r.amountReceived() : d.getGrandTotal();
            if (received.signum() > 0) {
                if (r.paymentMode() == null || r.paymentMode().isBlank()) {
                    throw WholesaleException.badRequest("Choose how the customer paid (cash, UPI, bank transfer, card or cheque)");
                }
                recordPayment(d, new WholesalePaymentRequest(documentDate, received, r.paymentMode(), r.paymentReference(), null),
                    username);
            }
        }
        WholesaleSalesDocument saved = documentRepository.save(d);

        if (q.getStatus() != WholesaleQuotationStatus.CONVERTED) {
            if (!q.getStatus().canMoveTo(WholesaleQuotationStatus.CONVERTED)) {
                throw WholesaleException.conflict("Quotation cannot move from " + q.getStatus() + " to CONVERTED");
            }
            q.setStatus(WholesaleQuotationStatus.CONVERTED);
            q.setStatusChangedAt(now);
            q.setStatusChangedBy(username);
        }
        return toResponse(saved, List.of());
    }

    private void assertConvertible(WholesaleQuotation q, boolean allowDuplicate) {
        WholesaleQuotationStatus status = q.getStatus();
        if (status == WholesaleQuotationStatus.CONVERTED) {
            List<WholesaleSalesDocument> active = documentRepository
                .findBySourceQuotation_QuotationIdOrderByCreatedAtAsc(q.getQuotationId()).stream()
                .filter(doc -> doc.getStatus().isActive()).toList();
            if (!active.isEmpty() && !allowDuplicate) {
                String numbers = String.join(", ", active.stream().map(WholesaleSalesDocument::getDocumentNumber).toList());
                throw WholesaleException.conflict("Quotation " + q.getQuotationNumber() + " is already converted (" + numbers
                    + "). Confirm \"convert again\" if you really want another document.");
            }
            return;
        }
        if (!status.isConvertible()) {
            String hint = switch (status) {
                case DRAFT -> " Issue it first.";
                case EXPIRED -> " It expired on " + q.getValidUntil() + "; duplicate it to quote again.";
                case REJECTED, CANCELLED -> " Duplicate it to quote again.";
                default -> "";
            };
            throw WholesaleException.conflict("Only issued or accepted quotations can be converted; this one is "
                + status.name().toLowerCase(Locale.ROOT) + "." + hint);
        }
    }

    // ── Drafts (Sales Receipt / Credit Note / Debit Note) ────

    @Transactional(readOnly = true)
    public WholesaleSalesDocumentResponse preview(WholesaleSalesDocumentRequest r) {
        WholesaleSalesDocument d = newDocument(WholesaleSalesDocumentType.valueOf(r.docType()));
        List<String> warnings = new ArrayList<>();
        applyDocument(d, r, warnings);
        return toResponse(d, warnings);
    }

    @Transactional
    public WholesaleSalesDocumentResponse create(WholesaleSalesDocumentRequest r, String username) {
        WholesaleSalesDocument d = newDocument(WholesaleSalesDocumentType.valueOf(r.docType()));
        d.setCreatedBy(username);
        List<String> warnings = new ArrayList<>();
        applyDocument(d, r, warnings);
        audit(d, "CREATED", username, "Draft created");
        return toResponse(documentRepository.save(d), warnings);
    }

    @Transactional
    public WholesaleSalesDocumentResponse update(Integer id, WholesaleSalesDocumentRequest r, String username) {
        WholesaleSalesDocument d = lock(id);
        if (!d.getStatus().isEditable()) {
            throw WholesaleException.conflict(label(d) + " is " + statusText(d) + " and can no longer be edited. "
                + "Issued documents are corrected with a Credit Note or Debit Note.");
        }
        if (d.getSourceQuotation() != null) {
            throw WholesaleException.conflict("Documents converted from a quotation keep the quoted values and cannot be edited");
        }
        if (!d.getDocType().name().equals(r.docType())) {
            throw WholesaleException.badRequest("The document type of a draft cannot be changed");
        }
        List<String> warnings = new ArrayList<>();
        applyDocument(d, r, warnings);
        audit(d, "UPDATED", username, "Draft updated");
        return toResponse(documentRepository.save(d), warnings);
    }

    // Phase 7 receipt endpoints map onto the general ones.
    public WholesaleSalesDocumentResponse previewReceipt(WholesaleSalesReceiptRequest r) {
        return preview(WholesaleSalesDocumentRequest.fromReceipt(r));
    }

    public WholesaleSalesDocumentResponse createReceipt(WholesaleSalesReceiptRequest r, String username) {
        return create(WholesaleSalesDocumentRequest.fromReceipt(r), username);
    }

    public WholesaleSalesDocumentResponse updateReceipt(Integer id, WholesaleSalesReceiptRequest r, String username) {
        return update(id, WholesaleSalesDocumentRequest.fromReceipt(r), username);
    }

    @Transactional
    public void deleteDraft(Integer id) {
        WholesaleSalesDocument d = lock(id);
        if (d.getStatus() != WholesaleSalesDocumentStatus.DRAFT) {
            throw WholesaleException.conflict("Only drafts can be deleted; " + label(d) + " is " + statusText(d) + ". Cancel it instead.");
        }
        documentRepository.delete(d);
    }

    private static WholesaleSalesDocument newDocument(WholesaleSalesDocumentType type) {
        WholesaleSalesDocument d = new WholesaleSalesDocument();
        d.setDocType(type);
        d.setStatus(WholesaleSalesDocumentStatus.DRAFT);
        d.setAmountPaid(BigDecimal.ZERO);
        d.setPaymentStatus("UNPAID");
        return d;
    }

    private void applyDocument(WholesaleSalesDocument d, WholesaleSalesDocumentRequest r, List<String> warnings) {
        LocalDate today = WholesaleDocumentNumberService.today();
        if (r.documentDate().isAfter(today)) throw WholesaleException.badRequest("Document date cannot be in the future");
        WholesaleDocumentSettings settings = numberService.settings(d.getDocType().name());

        d.setDocumentDate(r.documentDate());
        applySemantics(d, settings);
        String reason = WholesaleProductService.blankToNull(r.reason());
        if (reason == null && Boolean.TRUE.equals(settings.getReasonRequired())) {
            throw WholesaleException.badRequest("Enter a reason for this " + settings.getDisplayLabel());
        }
        d.setReason(reason);
        d.setNotes(WholesaleProductService.blankToNull(r.notes()));
        String terms = WholesaleProductService.blankToNull(r.terms());
        d.setTerms(terms != null ? terms : settings.getDefaultTerms());
        d.setOtherChargesLabel(WholesaleProductService.blankToNull(r.otherChargesLabel()));
        d.setOtherChargesAmount(r.otherChargesAmount() != null ? r.otherChargesAmount() : BigDecimal.ZERO);
        d.setOtherChargesGstPct(r.otherChargesGstPct() != null ? r.otherChargesGstPct() : BigDecimal.ZERO);
        if (d.getOtherChargesAmount().signum() > 0 && d.getOtherChargesLabel() == null) d.setOtherChargesLabel("Other charges");

        WholesaleCustomer customer = activeCustomer(r.customerId());
        snapshotParties(d, customer, warnings);
        applyReference(d, r, settings, customer);
        applyDueDate(d, r.dueDate(), settings);

        d.getItems().clear();
        if (d.getSalesDocumentId() != null) {
            // Delete old lines before inserting new ones (unique (sales_document_id, line_no)).
            documentRepository.flush();
        }
        String stockNote = d.getStockEffect() == WholesaleStockEffect.OUT ? "issuing needs this stock" : "no stock is taken out";
        int lineNo = 1;
        for (WholesaleQuotationItemRequest line : r.items()) {
            int n = lineNo++;
            WholesaleLinePricingService.PricedLine priced = linePricing.price(line.wholesaleProductId(), line.quantity(),
                line.rate(), line.gstPct(), r.documentDate(), n,
                d.getStockEffect() == WholesaleStockEffect.OUT ? warnings : new ArrayList<>(), stockNote);
            WholesaleProduct product = priced.product();
            WholesaleSalesDocumentItem i = new WholesaleSalesDocumentItem();
            i.setDocument(d);
            i.setLineNo(n);
            i.setProduct(product);
            i.setItemName(product.getProductName());
            i.setProductCode(product.getProductCode());
            i.setHsnCode(product.getHsnCode());
            i.setUnit(product.getUnit());
            i.setDescription(WholesaleProductService.blankToNull(line.description()));
            i.setQuantity(line.quantity());
            i.setRate(priced.rate());
            i.setRateSource(priced.rateSource());
            i.setDiscountPct(line.discountPct() != null ? line.discountPct() : BigDecimal.ZERO);
            i.setGstPct(priced.gstPct());
            d.getItems().add(i);
        }
        recalculate(d);
        if (d.getValueEffect() == WholesaleValueEffect.CREDIT && d.getReferenceDocument() != null) {
            warnings.addAll(creditProblems(d));
        }
    }

    /** Copies the configured meaning of the document type onto the document (frozen once issued). */
    private static void applySemantics(WholesaleSalesDocument d, WholesaleDocumentSettings s) {
        d.setDisplayLabel(s.getDisplayLabel() != null ? s.getDisplayLabel() : d.getDocType().getLabel());
        d.setPrintTitle(s.getPrintTitle() != null ? s.getPrintTitle() : d.getDocType().getPrintTitle());
        d.setGstClassification(s.getGstClassification());
        d.setValueEffect(s.getValueEffect());
        d.setStockEffect(s.getStockEffect());
    }

    /** Payable documents (other than Sales Receipts, which are settled at sale) get a due date. */
    private static void applyDueDate(WholesaleSalesDocument d, LocalDate requested, WholesaleDocumentSettings s) {
        if (!d.isChargeable() || d.getDocType() == WholesaleSalesDocumentType.SALES_RECEIPT) {
            d.setDueDate(null);
            return;
        }
        LocalDate due = requested != null ? requested : d.getDocumentDate().plusDays(s.getDefaultDueDays());
        if (due.isBefore(d.getDocumentDate())) throw WholesaleException.badRequest("Due date cannot be before the document date");
        d.setDueDate(due);
    }

    /**
     * Reference rules come from the document-type configuration (mode + allowed types). The referenced document must
     * belong to the same customer, be active and not be later than this document; its number and date are copied.
     */
    private void applyReference(WholesaleSalesDocument d, WholesaleSalesDocumentRequest r, WholesaleDocumentSettings s,
                                WholesaleCustomer customer) {
        d.setReferenceDocument(null);
        d.setReferenceQuotation(null);
        d.setReferenceDocType(null);
        d.setReferenceNumber(null);
        d.setReferenceDate(null);
        d.setReferenceExternal(false);

        String external = WholesaleProductService.blankToNull(r.externalReferenceNumber());
        int given = (r.referenceDocumentId() != null ? 1 : 0) + (r.referenceQuotationId() != null ? 1 : 0) + (external != null ? 1 : 0);
        String label = s.getDisplayLabel() != null ? s.getDisplayLabel() : d.getDocType().getLabel();
        if (given > 1) throw WholesaleException.badRequest("Give only one reference (a document, a quotation or an external invoice)");
        if (given == 0) {
            if (s.getReferenceMode() == WholesaleReferenceMode.REQUIRED) {
                throw WholesaleException.badRequest("A " + label + " must reference the original document (or an external invoice number and date)");
            }
            return;
        }
        if (s.getReferenceMode() == WholesaleReferenceMode.NONE) {
            throw WholesaleException.badRequest("A " + label + " cannot reference another document");
        }
        Set<String> allowed = s.allowedReferenceTypeSet();

        if (r.referenceDocumentId() != null) {
            WholesaleSalesDocument ref = documentRepository.findById(r.referenceDocumentId())
                .orElseThrow(() -> WholesaleException.badRequest("Referenced document #" + r.referenceDocumentId() + " not found"));
            if (!allowed.contains(ref.getDocType().name())) {
                throw WholesaleException.badRequest("A " + label + " cannot reference a " + ref.getDisplayLabel());
            }
            if (d.getSalesDocumentId() != null && ref.getSalesDocumentId().equals(d.getSalesDocumentId())) {
                throw WholesaleException.badRequest("A document cannot reference itself");
            }
            assertReferenceUsable(ref.getStatus().isActive(), ref.getCustomer().getWholesaleCustomerId(), ref.getDocumentDate(),
                customer, d.getDocumentDate(), ref.getDisplayLabel() + " " + ref.getDocumentNumber());
            d.setReferenceDocument(ref);
            d.setReferenceDocType(ref.getDocType().name());
            d.setReferenceNumber(ref.getDocumentNumber());
            d.setReferenceDate(ref.getDocumentDate());
        } else if (r.referenceQuotationId() != null) {
            if (!allowed.contains("QUOTATION")) throw WholesaleException.badRequest("A " + label + " cannot reference a quotation");
            WholesaleQuotation q = quotationRepository.findById(r.referenceQuotationId())
                .orElseThrow(() -> WholesaleException.badRequest("Referenced quotation #" + r.referenceQuotationId() + " not found"));
            boolean usable = q.getStatus() == WholesaleQuotationStatus.ISSUED || q.getStatus() == WholesaleQuotationStatus.ACCEPTED
                || q.getStatus() == WholesaleQuotationStatus.CONVERTED;
            assertReferenceUsable(usable, q.getCustomer().getWholesaleCustomerId(), q.getQuotationDate(), customer,
                d.getDocumentDate(), "Quotation " + q.getQuotationNumber());
            d.setReferenceQuotation(q);
            d.setReferenceDocType("QUOTATION");
            d.setReferenceNumber(q.getQuotationNumber());
            d.setReferenceDate(q.getQuotationDate());
        } else {
            if (r.externalReferenceDate() == null) {
                throw WholesaleException.badRequest("Enter the date of external invoice " + external);
            }
            if (r.externalReferenceDate().isAfter(d.getDocumentDate())) {
                throw WholesaleException.badRequest("The referenced invoice cannot be dated after this document");
            }
            d.setReferenceDocType("EXTERNAL");
            d.setReferenceNumber(external);
            d.setReferenceDate(r.externalReferenceDate());
            d.setReferenceExternal(true);
        }
    }

    private static void assertReferenceUsable(boolean active, Integer refCustomerId, LocalDate refDate, WholesaleCustomer customer,
                                              LocalDate docDate, String what) {
        if (!active) throw WholesaleException.badRequest(what + " is not active (draft or cancelled) and cannot be referenced");
        if (!refCustomerId.equals(customer.getWholesaleCustomerId())) {
            throw WholesaleException.badRequest(what + " belongs to a different customer");
        }
        if (refDate.isAfter(docDate)) throw WholesaleException.badRequest(what + " is dated after this document");
    }

    /**
     * For CREDIT documents against an internal document: products must be on the original, quantities credited so far
     * (all active credit documents) may not exceed what was supplied, and total credit may not exceed its grand total.
     */
    private List<String> creditProblems(WholesaleSalesDocument d) {
        WholesaleSalesDocument original = d.getReferenceDocument();
        Map<Integer, BigDecimal> supplied = new HashMap<>();
        for (WholesaleSalesDocumentItem i : original.getItems()) {
            supplied.merge(i.getProduct().getWholesaleProductId(), i.getQuantity(), BigDecimal::add);
        }
        Map<Integer, BigDecimal> credited = new HashMap<>();
        BigDecimal creditedValue = BigDecimal.ZERO;
        for (WholesaleSalesDocument other : documentRepository.findByReferenceDocument_SalesDocumentId(original.getSalesDocumentId())) {
            if (other == d || other.getSalesDocumentId() != null && other.getSalesDocumentId().equals(d.getSalesDocumentId())) continue;
            if (!other.getStatus().isActive() || other.getValueEffect() != WholesaleValueEffect.CREDIT) continue;
            creditedValue = creditedValue.add(other.getGrandTotal());
            for (WholesaleSalesDocumentItem i : other.getItems()) {
                credited.merge(i.getProduct().getWholesaleProductId(), i.getQuantity(), BigDecimal::add);
            }
        }
        List<String> problems = new ArrayList<>();
        for (WholesaleSalesDocumentItem i : d.getItems()) {
            Integer pid = i.getProduct().getWholesaleProductId();
            BigDecimal max = supplied.get(pid);
            if (max == null) {
                problems.add("'" + i.getItemName() + "' is not on " + original.getDocumentNumber());
                continue;
            }
            BigDecimal remaining = max.subtract(credited.getOrDefault(pid, BigDecimal.ZERO));
            BigDecimal wanted = d.getItems().stream().filter(x -> x.getProduct().getWholesaleProductId().equals(pid))
                .map(WholesaleSalesDocumentItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (wanted.compareTo(remaining) > 0) {
                problems.add("'" + i.getItemName() + "': only " + remaining.stripTrailingZeros().toPlainString() + " " + i.getUnit()
                    + " of " + original.getDocumentNumber() + " can still be credited");
            }
        }
        BigDecimal remainingValue = original.getGrandTotal().subtract(creditedValue);
        if (d.getGrandTotal().compareTo(remainingValue) > 0) {
            problems.add("Credit of " + d.getGrandTotal().toPlainString() + " is more than the " + remainingValue.toPlainString()
                + " still open on " + original.getDocumentNumber());
        }
        return problems.stream().distinct().toList();
    }

    // ── Issue ────────────────────────────────────────────────

    /** DRAFT → ISSUED (or PARTIALLY_PAID / PAID when a payment is recorded at the same time). */
    @Transactional
    public WholesaleSalesDocumentResponse issue(Integer id, WholesaleIssueRequest r, String username) {
        WholesaleSalesDocument d = lock(id);
        if (d.getStatus() != WholesaleSalesDocumentStatus.DRAFT) {
            throw WholesaleException.conflict(label(d) + " is already " + statusText(d));
        }
        LocalDate today = WholesaleDocumentNumberService.today();
        if (d.getDocumentDate().isAfter(today)) throw WholesaleException.badRequest("Receipt date cannot be in the future");

        // Final snapshot of customer + profile + document-type semantics; amounts from the STORED rates (no re-pricing).
        List<String> warnings = new ArrayList<>();
        WholesaleDocumentSettings settings = numberService.settings(d.getDocType().name());
        snapshotParties(d, activeCustomer(d.getCustomer().getWholesaleCustomerId()), warnings);
        applySemantics(d, settings);
        recalculate(d);
        if (d.getReferenceDocument() != null && !d.getReferenceDocument().getStatus().isActive()) {
            throw WholesaleException.conflict("Referenced " + d.getReferenceDocument().getDisplayLabel() + " "
                + d.getReferenceNumber() + " has been cancelled; change the reference before issuing");
        }
        if (d.getValueEffect() == WholesaleValueEffect.CREDIT && d.getReferenceDocument() != null) {
            List<String> problems = creditProblems(d);
            if (!problems.isEmpty()) throw WholesaleException.conflict(String.join("; ", problems));
        }
        if (d.getValueEffect() == WholesaleValueEffect.CREDIT && r != null && r.payment() != null) {
            throw WholesaleException.badRequest("A credit document does not take customer payments");
        }

        issueInto(d, username, LocalDateTime.now());
        if (r != null && r.payment() != null) {
            recordPayment(d, r.payment(), username);
        }
        return toResponse(documentRepository.save(d), warnings);
    }

    /**
     * Assigns the number, marks the document issued and applies its stock effect through the inventory ledger
     * (all-or-nothing; the document is saved first so the ledger rows point at it).
     */
    private void issueInto(WholesaleSalesDocument d, String username, LocalDateTime now) {
        d.setDocumentNumber(numberService.next(d.getDocType().name(), d.getDocumentDate(),
            documentRepository::existsByDocumentNumber));
        moveTo(d, WholesaleSalesDocumentStatus.ISSUED);
        d.setIssuedAt(now);
        d.setIssuedBy(username);
        if (d.getCreatedBy() == null) d.setCreatedBy(username);
        if (d.getSalesDocumentId() == null) documentRepository.saveAndFlush(d);
        postIssueStock(d, username);
        audit(d, "ISSUED", username, "Issued as " + d.getDocumentNumber() + " (" + d.getPrintTitle() + "; GST "
            + d.getGstClassification() + ", " + d.getValueEffect() + ", stock " + d.getStockEffect()
            + (d.getReferenceNumber() != null ? "; ref " + d.getReferenceNumber() : "") + ")");
    }

    // ── Payments ─────────────────────────────────────────────

    @Transactional
    public WholesaleSalesDocumentResponse addPayment(Integer id, WholesalePaymentRequest r, String username) {
        WholesaleSalesDocument d = lock(id);
        recordPayment(d, r, username);
        return toResponse(documentRepository.save(d), List.of());
    }

    @Transactional
    public WholesaleSalesDocumentResponse voidPayment(Integer id, Integer paymentId, String reason, String username) {
        WholesaleSalesDocument d = lock(id);
        if (!d.getStatus().isActive()) {
            throw WholesaleException.conflict("Payments of a " + statusText(d) + " document cannot be changed");
        }
        WholesaleSalesPayment p = d.getPayments().stream().filter(x -> x.getPaymentId().equals(paymentId)).findFirst()
            .orElseThrow(() -> WholesaleException.notFound("Payment #" + paymentId + " not found on " + label(d)));
        if (!p.isRecorded()) throw WholesaleException.conflict("This payment is already voided");
        p.setStatus(WholesaleSalesPayment.VOIDED);
        p.setVoidedAt(LocalDateTime.now());
        p.setVoidedBy(username);
        p.setVoidReason(reason.trim());
        applyPaidAmount(d, d.getAmountPaid().subtract(p.getAmount()));
        audit(d, "PAYMENT_VOIDED", username, p.getAmount().toPlainString() + " of " + p.getPaymentDate() + " voided: " + reason.trim());
        d.setPaymentMode(d.getPayments().stream().filter(WholesaleSalesPayment::isRecorded)
            .reduce((a, b) -> b).map(WholesaleSalesPayment::getPaymentMode).orElse(null));
        return toResponse(documentRepository.save(d), List.of());
    }

    private void recordPayment(WholesaleSalesDocument d, WholesalePaymentRequest r, String username) {
        if (!d.isChargeable()) {
            throw WholesaleException.conflict(label(d) + " is a credit document; customer payments are not recorded on it");
        }
        if (!d.getStatus().acceptsPayments()) {
            String why = switch (d.getStatus()) {
                case DRAFT -> " Issue it first.";
                case PAID -> " It is already fully paid.";
                default -> "";
            };
            throw WholesaleException.conflict("Payments cannot be recorded on a " + statusText(d) + " document." + why);
        }
        LocalDate today = WholesaleDocumentNumberService.today();
        LocalDate date = r.paymentDate() != null ? r.paymentDate() : today;
        if (date.isAfter(today)) throw WholesaleException.badRequest("Payment date cannot be in the future");
        if (date.isBefore(d.getDocumentDate())) {
            throw WholesaleException.badRequest("Payment date cannot be before the document date " + d.getDocumentDate());
        }
        BigDecimal balance = d.getGrandTotal().subtract(d.getAmountPaid());
        if (r.amount().compareTo(balance) > 0) {
            throw WholesaleException.badRequest("Payment of " + r.amount().toPlainString()
                + " is more than the balance due of " + balance.toPlainString());
        }
        WholesaleSalesPayment p = new WholesaleSalesPayment();
        p.setDocument(d);
        p.setPaymentDate(date);
        p.setAmount(r.amount());
        p.setPaymentMode(WholesalePaymentMode.valueOf(r.paymentMode()));
        p.setReference(WholesaleProductService.blankToNull(r.reference()));
        p.setNotes(WholesaleProductService.blankToNull(r.notes()));
        p.setCreatedBy(username);
        d.getPayments().add(p);
        d.setPaymentMode(p.getPaymentMode());
        d.setPaymentReference(p.getReference());
        applyPaidAmount(d, d.getAmountPaid().add(r.amount()));
        audit(d, "PAYMENT_RECORDED", username, r.amount().toPlainString() + " by " + r.paymentMode() + " on " + date
            + (p.getReference() != null ? " (ref " + p.getReference() + ")" : ""));
    }

    /** Sets amount paid and moves ISSUED / PARTIALLY_PAID / PAID accordingly (validated transition). */
    private void applyPaidAmount(WholesaleSalesDocument d, BigDecimal paid) {
        WholesaleSalesDocumentStatus next = WholesaleSalesDocumentStatus.forPayment(paid, d.getGrandTotal());
        if (next != d.getStatus()) moveTo(d, next);
        d.setAmountPaid(paid);
        d.setPaymentStatus(WholesaleSalesDocumentStatus.paymentStatusFor(paid, d.getGrandTotal()));
    }

    // ── Cancel ───────────────────────────────────────────────

    /**
     * Any non-cancelled document → CANCELLED (reason required). Issued documents put their stock back; recorded
     * payments stay in the history (refund them outside the system). A converted quotation returns to ACCEPTED when
     * it has no active document left.
     */
    @Transactional
    public WholesaleSalesDocumentResponse cancel(Integer id, String reason, String username) {
        // Lock order (document, then quotation, then product rows) matches conversion (quotation, then products).
        WholesaleSalesDocument d = lock(id);
        WholesaleSalesDocumentStatus before = d.getStatus();
        if (!before.canMoveTo(WholesaleSalesDocumentStatus.CANCELLED)) {
            throw WholesaleException.conflict(label(d) + " is already " + statusText(d));
        }
        WholesaleQuotation q = d.getSourceQuotation() != null
            ? quotationRepository.findByIdForUpdate(d.getSourceQuotation().getQuotationId()).orElse(null) : null;

        if (before.isActive()) {
            reverseStock(d, reason.trim(), username); // undo exactly what the document moved
        }
        LocalDateTime now = LocalDateTime.now();
        moveTo(d, WholesaleSalesDocumentStatus.CANCELLED);
        d.setCancelledAt(now);
        d.setCancelledBy(username);
        d.setCancelReason(reason.trim());
        audit(d, "CANCELLED", username, reason.trim());
        documentRepository.saveAndFlush(d);

        if (q != null && q.getStatus().canRevertConversion()
            && documentRepository.countBySourceQuotation_QuotationIdAndStatusIn(q.getQuotationId(), ACTIVE) == 0) {
            q.setStatus(WholesaleQuotationStatus.ACCEPTED);
            q.setStatusChangedAt(now);
            q.setStatusChangedBy(username + " (conversion cancelled)");
        }
        List<String> warnings = d.getAmountPaid().signum() > 0
            ? List.of("₹" + d.getAmountPaid().toPlainString() + " had been received on this document; refund it to the customer if needed.")
            : List.of();
        return toResponse(d, warnings);
    }

    // ── Queries ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WholesaleSalesDocumentSummary> list(String type, String status, String q, LocalDate from, LocalDate to, Integer limit) {
        List<WholesaleSalesDocumentType> types = parse(type, WholesaleSalesDocumentType.class, "document type");
        List<WholesaleSalesDocumentStatus> statuses = parse(status, WholesaleSalesDocumentStatus.class, "status");
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int size = limit == null || limit <= 0 ? MAX_LIST : Math.min(limit, MAX_LIST);
        return documentRepository.search(types, statuses, from != null ? from : MIN_DATE, to != null ? to : MAX_DATE,
                "%" + term + "%", PageRequest.of(0, size)).stream()
            .map(d -> new WholesaleSalesDocumentSummary(d.getSalesDocumentId(), d.getDocType().name(), d.getDisplayLabel(),
                displayNumber(d), d.getDocumentDate(), d.getStatus().name(), d.getCustomer().getWholesaleCustomerId(), d.getCustomerName(),
                d.getCustomerBusinessName(), d.getGrandTotal(), d.getAmountPaid(), d.getGrandTotal().subtract(d.getAmountPaid()),
                d.getPaymentStatus(), d.getPaymentMode() != null ? d.getPaymentMode().name() : null, d.getDueDate(),
                d.getSourceQuotation() != null ? d.getSourceQuotation().getQuotationId() : null,
                d.getSourceQuotation() != null ? d.getSourceQuotation().getQuotationNumber() : null,
                d.getReferenceNumber(), d.getValueEffect().name()))
            .toList();
    }

    @Transactional(readOnly = true)
    public WholesaleSalesDocumentResponse get(Integer id) {
        return toResponse(documentRepository.findByIdWithItems(id)
            .orElseThrow(() -> WholesaleException.notFound("Sales document #" + id + " not found")), List.of());
    }

    // ── Helpers ──────────────────────────────────────────────

    private WholesaleSalesDocument lock(Integer id) {
        return documentRepository.findByIdForUpdate(id)
            .orElseThrow(() -> WholesaleException.notFound("Sales document #" + id + " not found"));
    }

    private static void moveTo(WholesaleSalesDocument d, WholesaleSalesDocumentStatus next) {
        if (!d.getStatus().canMoveTo(next)) {
            throw WholesaleException.conflict(label(d) + " cannot move from " + d.getStatus() + " to " + next);
        }
        d.setStatus(next);
    }

    private WholesaleCustomer activeCustomer(Integer customerId) {
        WholesaleCustomer c = customerRepository.findById(customerId)
            .orElseThrow(() -> WholesaleException.badRequest("Wholesale customer #" + customerId + " not found"));
        if (!Boolean.TRUE.equals(c.getIsActive())) {
            throw WholesaleException.badRequest("Customer '" + c.getCustomerName() + "' is inactive");
        }
        return c;
    }

    /** Copies the current customer + business profile and decides CGST+SGST vs IGST (direct receipts). */
    private void snapshotParties(WholesaleSalesDocument d, WholesaleCustomer c, List<String> warnings) {
        WholesaleDocumentProfile firm = profileService.documentProfile(); // 409 if the profile is incomplete
        d.setFirmSnapshotJson(toJson(firm));
        d.setFirmStateCode(firm.stateCode());
        d.setCustomer(c);
        d.setCustomerName(c.getCustomerName());
        d.setCustomerBusinessName(c.getBusinessName());
        d.setCustomerGstNumber(c.getGstNumber());
        d.setCustomerPhone(c.getPhone());
        d.setCustomerEmail(c.getEmail());
        d.setBillingAddress(c.getBillingAddress());
        d.setBillingCity(c.getCity());
        d.setBillingStateCode(c.getStateCode());
        d.setBillingStateName(c.getStateName());
        d.setBillingPinCode(c.getPinCode());
        d.setShippingAddress(c.getShippingAddress());
        d.setShippingCity(c.getShippingCity());
        d.setShippingStateCode(c.getShippingStateCode());
        d.setShippingStateName(c.getShippingStateName());
        d.setShippingPinCode(c.getShippingPinCode());
        String place = c.getShippingStateCode() != null ? c.getShippingStateCode() : c.getStateCode();
        d.setPlaceOfSupplyStateCode(place);
        d.setInterstate(place != null && !place.equals(firm.stateCode()));
        if (place == null) {
            warnings.add("Customer has no state, so GST is calculated as intra-state (CGST + SGST). Add the customer's state to be sure.");
        }
    }

    /** Recomputes all amounts from the STORED rates (never re-prices). */
    private static void recalculate(WholesaleSalesDocument d) {
        boolean interstate = Boolean.TRUE.equals(d.getInterstate());
        List<WholesaleQuotationCalculator.LineInput> inputs = d.getItems().stream()
            .map(i -> new WholesaleQuotationCalculator.LineInput(i.getQuantity(), i.getRate(), i.getDiscountPct(), i.getGstPct()))
            .toList();
        WholesaleQuotationCalculator.Totals t = WholesaleQuotationCalculator.totals(inputs, interstate,
            d.getOtherChargesAmount(), d.getOtherChargesGstPct());
        for (int n = 0; n < d.getItems().size(); n++) {
            WholesaleSalesDocumentItem i = d.getItems().get(n);
            WholesaleQuotationCalculator.LineResult r = t.lines().get(n);
            i.setGrossAmount(r.gross());
            i.setDiscountAmount(r.discount());
            i.setTaxableAmount(r.taxable());
            i.setCgstAmount(r.cgst());
            i.setSgstAmount(r.sgst());
            i.setIgstAmount(r.igst());
            i.setGstAmount(r.gst());
            i.setTotalAmount(r.total());
        }
        d.setGrossAmount(t.gross());
        d.setDiscountAmount(t.discount());
        d.setSubtotal(t.subtotal());
        d.setOtherChargesAmount(t.otherCharges());
        d.setOtherChargesGstAmount(t.otherChargesGst());
        d.setCgstAmount(t.cgst());
        d.setSgstAmount(t.sgst());
        d.setIgstAmount(t.igst());
        d.setGstAmount(t.gst());
        d.setRoundOff(t.roundOff());
        d.setGrandTotal(t.grandTotal());
    }

    private static void copyParties(WholesaleQuotation q, WholesaleSalesDocument d) {
        d.setCustomer(q.getCustomer());
        d.setCustomerName(q.getCustomerName());
        d.setCustomerBusinessName(q.getCustomerBusinessName());
        d.setCustomerGstNumber(q.getCustomerGstNumber());
        d.setCustomerPhone(q.getCustomerPhone());
        d.setCustomerEmail(q.getCustomerEmail());
        d.setBillingAddress(q.getBillingAddress());
        d.setBillingCity(q.getBillingCity());
        d.setBillingStateCode(q.getBillingStateCode());
        d.setBillingStateName(q.getBillingStateName());
        d.setBillingPinCode(q.getBillingPinCode());
        d.setShippingAddress(q.getShippingAddress());
        d.setShippingCity(q.getShippingCity());
        d.setShippingStateCode(q.getShippingStateCode());
        d.setShippingStateName(q.getShippingStateName());
        d.setShippingPinCode(q.getShippingPinCode());
        d.setFirmStateCode(q.getFirmStateCode());
        d.setPlaceOfSupplyStateCode(q.getPlaceOfSupplyStateCode());
        d.setInterstate(q.getInterstate());
    }

    /** Totals copied verbatim — the quotation's captured prices are never recalculated. */
    private static void copyTotals(WholesaleQuotation q, WholesaleSalesDocument d) {
        d.setGrossAmount(q.getGrossAmount());
        d.setDiscountAmount(q.getDiscountAmount());
        d.setSubtotal(q.getSubtotal());
        d.setOtherChargesLabel(q.getOtherChargesLabel());
        d.setOtherChargesAmount(q.getOtherChargesAmount());
        d.setOtherChargesGstPct(q.getOtherChargesGstPct());
        d.setOtherChargesGstAmount(q.getOtherChargesGstAmount());
        d.setCgstAmount(q.getCgstAmount());
        d.setSgstAmount(q.getSgstAmount());
        d.setIgstAmount(q.getIgstAmount());
        d.setGstAmount(q.getGstAmount());
        d.setRoundOff(q.getRoundOff());
        d.setGrandTotal(q.getGrandTotal());
    }

    private static void copyItems(WholesaleQuotation q, WholesaleSalesDocument d) {
        for (WholesaleQuotationItem src : q.getItems()) {
            WholesaleSalesDocumentItem i = new WholesaleSalesDocumentItem();
            i.setDocument(d);
            i.setSourceQuotationItem(src);
            i.setLineNo(src.getLineNo());
            i.setProduct(src.getProduct());
            i.setItemName(src.getItemName());
            i.setProductCode(src.getProductCode());
            i.setHsnCode(src.getHsnCode());
            i.setUnit(src.getUnit());
            i.setDescription(src.getDescription());
            i.setQuantity(src.getQuantity());
            i.setRate(src.getRate());
            i.setRateSource(src.getRateSource());
            i.setDiscountPct(src.getDiscountPct());
            i.setGrossAmount(src.getGrossAmount());
            i.setDiscountAmount(src.getDiscountAmount());
            i.setTaxableAmount(src.getTaxableAmount());
            i.setGstPct(src.getGstPct());
            i.setCgstAmount(src.getCgstAmount());
            i.setSgstAmount(src.getSgstAmount());
            i.setIgstAmount(src.getIgstAmount());
            i.setGstAmount(src.getGstAmount());
            i.setTotalAmount(src.getTotalAmount());
            d.getItems().add(i);
        }
    }

    /** Ledger transaction type for a document's own (frozen) stock effect. */
    private static WholesaleInventoryTransactionType transactionType(WholesaleSalesDocument d) {
        if (d.getStockEffect() == WholesaleStockEffect.IN) return WholesaleInventoryTransactionType.RETURN;
        return switch (d.getDocType()) {
            case SALES_RECEIPT -> WholesaleInventoryTransactionType.SALE;
            case CREDIT_NOTE -> WholesaleInventoryTransactionType.CREDIT;
            case DEBIT_NOTE -> WholesaleInventoryTransactionType.DEBIT;
        };
    }

    /** Applies the document's stock effect (OUT / IN / NONE) as ledger movements sourced from this document. */
    private void postIssueStock(WholesaleSalesDocument d, String username) {
        WholesaleStockEffect effect = d.getStockEffect();
        if (effect == null || effect == WholesaleStockEffect.NONE) return;
        WholesaleInventoryTransactionType type = transactionType(d);
        String note = d.getDisplayLabel() + " to " + d.getCustomerName();
        List<WholesaleInventoryService.Movement> movements = new ArrayList<>();
        for (WholesaleSalesDocumentItem i : d.getItems()) {
            movements.add(effect == WholesaleStockEffect.OUT
                ? WholesaleInventoryService.Movement.out(i.getProduct(), type, d.getDocType().name(), d.getSalesDocumentId(),
                    d.getDocumentNumber(), i.getQuantity(), i.getRate(), d.getDocumentDate(), note)
                : WholesaleInventoryService.Movement.in(i.getProduct(), type, d.getDocType().name(), d.getSalesDocumentId(),
                    d.getDocumentNumber(), i.getQuantity(), i.getRate(), d.getDocumentDate(), note));
        }
        inventoryService.post(movements, username);
    }

    /**
     * Undoes the document's stock movements with REVERSAL rows. Documents issued before the stock ledger existed
     * have no rows; their recorded stock effect is reversed explicitly instead.
     */
    private void reverseStock(WholesaleSalesDocument d, String reason, String username) {
        LocalDate today = WholesaleDocumentNumberService.today();
        int reversed = inventoryService.reverseDocument(d.getDocType().name(), d.getSalesDocumentId(), d.getDocumentNumber(),
            today, "Cancelled: " + reason, username);
        WholesaleStockEffect effect = d.getStockEffect();
        if (reversed > 0 || effect == null || effect == WholesaleStockEffect.NONE) return;
        List<WholesaleInventoryService.Movement> movements = new ArrayList<>();
        for (WholesaleSalesDocumentItem i : d.getItems()) {
            String note = "Cancelled (issued before the stock ledger): " + reason;
            movements.add(effect == WholesaleStockEffect.OUT
                ? WholesaleInventoryService.Movement.in(i.getProduct(), WholesaleInventoryTransactionType.REVERSAL,
                    d.getDocType().name(), d.getSalesDocumentId(), d.getDocumentNumber(), i.getQuantity(), i.getRate(), today, note)
                : WholesaleInventoryService.Movement.out(i.getProduct(), WholesaleInventoryTransactionType.REVERSAL,
                    d.getDocType().name(), d.getSalesDocumentId(), d.getDocumentNumber(), i.getQuantity(), i.getRate(), today, note));
        }
        inventoryService.post(movements, username);
    }

    private static void audit(WholesaleSalesDocument d, String type, String by, String details) {
        d.getEvents().add(WholesaleDocumentEvent.of(d, type, by, details));
    }

    private String toJson(WholesaleDocumentProfile firm) {
        try {
            return objectMapper.writeValueAsString(firm);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store business profile snapshot", e);
        }
    }

    private static String label(WholesaleSalesDocument d) {
        return (d.getDisplayLabel() != null ? d.getDisplayLabel() : d.getDocType().getLabel()) + " " + displayNumber(d);
    }

    private static String statusText(WholesaleSalesDocument d) {
        return d.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String displayNumber(WholesaleSalesDocument d) {
        if (d.getDocumentNumber() != null) return d.getDocumentNumber();
        return d.getSalesDocumentId() != null ? "Draft #" + d.getSalesDocumentId() : "Draft";
    }

    private static <E extends Enum<E>> List<E> parse(String raw, Class<E> type, String what) {
        if (raw == null || raw.isBlank()) return List.of(type.getEnumConstants());
        List<E> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            try {
                out.add(Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw WholesaleException.badRequest("Unknown " + what + " " + s.trim());
            }
        }
        return out;
    }

    static List<String> allowedActions(WholesaleSalesDocument d) {
        List<String> actions = new ArrayList<>();
        WholesaleSalesDocumentStatus s = d.getStatus();
        if (s.isEditable() && d.getSourceQuotation() == null) actions.add("EDIT");
        if (s == WholesaleSalesDocumentStatus.DRAFT) actions.addAll(List.of("DELETE", "ISSUE"));
        if (s.acceptsPayments() && d.isChargeable()) actions.add("ADD_PAYMENT");
        if (s.isActive() && d.getPayments().stream().anyMatch(WholesaleSalesPayment::isRecorded)) actions.add("VOID_PAYMENT");
        if (s.canMoveTo(WholesaleSalesDocumentStatus.CANCELLED)) actions.add("CANCEL");
        actions.addAll(List.of("PRINT", "PDF", "SHARE"));
        return actions;
    }

    private WholesaleSalesDocumentResponse toResponse(WholesaleSalesDocument d, List<String> warnings) {
        WholesaleDocumentProfile firm;
        try {
            firm = objectMapper.readValue(d.getFirmSnapshotJson(), WholesaleDocumentProfile.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored business profile snapshot is unreadable", e);
        }
        WholesaleQuotationResponse.Customer customer = new WholesaleQuotationResponse.Customer(
            d.getCustomer().getWholesaleCustomerId(), d.getCustomerName(), d.getCustomerBusinessName(), d.getCustomerGstNumber(),
            d.getCustomerPhone(), d.getCustomerEmail(), d.getBillingAddress(), d.getBillingCity(), d.getBillingStateCode(),
            d.getBillingStateName(), d.getBillingPinCode(), d.getShippingAddress(), d.getShippingCity(),
            d.getShippingStateCode(), d.getShippingStateName(), d.getShippingPinCode());
        List<WholesaleSalesDocumentResponse.Item> items = d.getItems().stream().map(i -> new WholesaleSalesDocumentResponse.Item(
            i.getSalesDocumentItemId(), i.getLineNo(),
            i.getSourceQuotationItem() != null ? i.getSourceQuotationItem().getQuotationItemId() : null,
            i.getProduct().getWholesaleProductId(), i.getItemName(), i.getProductCode(), i.getHsnCode(), i.getUnit(),
            i.getDescription(), i.getQuantity(), i.getRate(), i.getRateSource(), i.getDiscountPct(), i.getGrossAmount(),
            i.getDiscountAmount(), i.getTaxableAmount(), i.getGstPct(), i.getCgstAmount(), i.getSgstAmount(),
            i.getIgstAmount(), i.getGstAmount(), i.getTotalAmount())).toList();
        WholesaleQuotationResponse.Totals totals = new WholesaleQuotationResponse.Totals(d.getGrossAmount(),
            d.getDiscountAmount(), d.getSubtotal(), d.getOtherChargesLabel(), d.getOtherChargesAmount(),
            d.getOtherChargesGstPct(), d.getOtherChargesGstAmount(), d.getCgstAmount(), d.getSgstAmount(),
            d.getIgstAmount(), d.getGstAmount(), d.getRoundOff(), d.getGrandTotal());
        WholesaleSalesDocumentResponse.Payment payment = new WholesaleSalesDocumentResponse.Payment(
            d.getPaymentMode() != null ? d.getPaymentMode().name() : null, d.getPaymentReference(), d.getAmountPaid(),
            d.getPaymentStatus(), d.getDueDate(), d.getGrandTotal().subtract(d.getAmountPaid()));
        List<WholesaleSalesDocumentResponse.PaymentRecord> payments = d.getPayments().stream()
            .map(p -> new WholesaleSalesDocumentResponse.PaymentRecord(p.getPaymentId(), p.getPaymentDate(), p.getAmount(),
                p.getPaymentMode().name(), p.getReference(), p.getNotes(), p.getStatus(), p.getCreatedAt(), p.getCreatedBy(),
                p.getVoidedAt(), p.getVoidedBy(), p.getVoidReason()))
            .toList();
        WholesaleQuotation src = d.getSourceQuotation();
        WholesaleSalesDocumentResponse.Semantics semantics = new WholesaleSalesDocumentResponse.Semantics(
            d.getGstClassification().name(), d.getValueEffect().name(), d.getStockEffect().name());
        WholesaleSalesDocumentResponse.Reference reference = d.getReferenceDocType() == null ? null
            : new WholesaleSalesDocumentResponse.Reference(d.getReferenceDocType(),
                d.getReferenceDocument() != null ? d.getReferenceDocument().getSalesDocumentId() : null,
                d.getReferenceQuotation() != null ? d.getReferenceQuotation().getQuotationId()
                    : "QUOTATION".equals(d.getReferenceDocType()) && src != null ? src.getQuotationId() : null,
                d.getReferenceNumber(), d.getReferenceDate());
        List<WholesaleSalesDocumentResponse.AuditEvent> trail = d.getEvents().stream()
            .map(e -> new WholesaleSalesDocumentResponse.AuditEvent(e.getEventType(), e.getEventAt(), e.getEventBy(), e.getDetails()))
            .toList();
        return new WholesaleSalesDocumentResponse(d.getSalesDocumentId(), d.getDocType().name(), d.getDisplayLabel(),
            d.getPrintTitle(), displayNumber(d), d.getDocumentDate(), d.getStatus().name(),
            src != null ? src.getQuotationId() : null, src != null ? src.getQuotationNumber() : null, semantics, reference,
            d.getReason(), firm, customer,
            d.getPlaceOfSupplyStateCode(), d.getInterstate(), items, totals, payment, d.getNotes(), d.getTerms(),
            d.getCreatedAt(), d.getCreatedBy(), d.getIssuedAt(), d.getIssuedBy(), d.getConvertedAt(), d.getConvertedBy(),
            d.getCancelledAt(), d.getCancelledBy(), d.getCancelReason(), payments, trail, allowedActions(d), warnings);
    }
}
