package com.example.mybill.wholesale.service;

import com.example.mybill.service.Supplier;
import com.example.mybill.service.SupplierRepository;
import com.example.mybill.wholesale.dto.WholesalePurchaseItemRequest;
import com.example.mybill.wholesale.dto.WholesalePurchaseItemResponse;
import com.example.mybill.wholesale.dto.WholesalePurchaseRequest;
import com.example.mybill.wholesale.dto.WholesalePurchaseResponse;
import com.example.mybill.wholesale.entity.WholesaleInventoryTransactionType;
import com.example.mybill.wholesale.entity.WholesaleProduct;
import com.example.mybill.wholesale.entity.WholesalePurchase;
import com.example.mybill.wholesale.entity.WholesalePurchaseItem;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import com.example.mybill.wholesale.repository.WholesalePurchaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wholesale purchase bills. Stock goes into wholesale_products only — the retail purchase,
 * product and inventory services are not involved.
 */
@Service
public class WholesalePurchaseService {

    @Autowired private WholesalePurchaseRepository purchaseRepository;
    @Autowired private WholesaleProductRepository productRepository;
    @Autowired private WholesaleProductService productService;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private WholesaleInventoryService inventoryService;

    @Transactional(readOnly = true)
    public List<WholesalePurchaseResponse> list() {
        return purchaseRepository.findAllWithItems().stream().map(WholesalePurchaseService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public WholesalePurchaseResponse get(Integer id) {
        return toResponse(findWithItems(id));
    }

    @Transactional
    public WholesalePurchaseResponse create(WholesalePurchaseRequest request, String username) {
        Supplier supplier = supplierRepository.findById(request.supplierId())
            .orElseThrow(() -> WholesaleException.badRequest("Supplier #" + request.supplierId() + " not found"));

        String invoiceNumber = request.invoiceNumber().trim();
        if (purchaseRepository.existsBySupplier_SupplierIdAndInvoiceNumberIgnoreCaseAndStatus(supplier.getSupplierId(), invoiceNumber, "ACTIVE")) {
            throw WholesaleException.conflict("Invoice " + invoiceNumber + " from " + supplier.getSupplierName()
                + " is already recorded as a wholesale purchase");
        }

        boolean interstate = Boolean.TRUE.equals(request.interstate());
        WholesalePurchase purchase = new WholesalePurchase();
        purchase.setSupplier(supplier);
        purchase.setInvoiceNumber(invoiceNumber);
        purchase.setInvoiceDate(request.invoiceDate());
        purchase.setInterstate(interstate);
        purchase.setPaymentDueDate(request.paymentDueDate());
        purchase.setNotes(WholesaleProductService.blankToNull(request.notes()));

        Set<String> newCodes = new HashSet<>();
        BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO,
                   igst = BigDecimal.ZERO, gst = BigDecimal.ZERO, total = BigDecimal.ZERO;

        List<WholesalePurchaseItemRequest> lines = request.items();
        for (int i = 0; i < lines.size(); i++) {
            WholesalePurchaseItemRequest line = lines.get(i);
            WholesaleProduct product = resolveProduct(line, i + 1, supplier, newCodes);

            WholesaleTaxCalculator.LineTax tax =
                WholesaleTaxCalculator.compute(line.quantity(), line.purchaseRate(), line.gstPct(), interstate);

            WholesalePurchaseItem item = new WholesalePurchaseItem();
            item.setProduct(product);
            item.setHsnCode(product.getHsnCode());
            item.setQuantity(line.quantity());
            item.setPurchaseRate(line.purchaseRate());
            item.setGstPct(line.gstPct());
            item.setTaxableAmount(tax.taxable());
            item.setCgstAmount(tax.cgst());
            item.setSgstAmount(tax.sgst());
            item.setIgstAmount(tax.igst());
            item.setGstAmount(tax.gst());
            item.setTotalAmount(tax.total());
            purchase.addItem(item);

            taxable = taxable.add(tax.taxable());
            cgst = cgst.add(tax.cgst());
            sgst = sgst.add(tax.sgst());
            igst = igst.add(tax.igst());
            gst = gst.add(tax.gst());
            total = total.add(tax.total());
        }

        BigDecimal paid = request.paidAmount() != null ? request.paidAmount() : BigDecimal.ZERO;
        if (paid.compareTo(total) > 0) {
            throw WholesaleException.badRequest("Paid amount (" + paid + ") cannot exceed the invoice total (" + total + ")");
        }

        purchase.setTaxableAmount(taxable);
        purchase.setCgstAmount(cgst);
        purchase.setSgstAmount(sgst);
        purchase.setIgstAmount(igst);
        purchase.setGstAmount(gst);
        purchase.setTotalAmount(total);
        purchase.setPaidAmount(paid);
        purchase.setPaymentStatus(WholesaleTaxCalculator.paymentStatus(paid, total));

        purchase.setCreatedBy(username);
        WholesalePurchase saved = purchaseRepository.save(purchase);

        // Stock in through the inventory ledger (source: this purchase); remember the latest rate and supplier.
        List<WholesaleInventoryService.Movement> movements = new ArrayList<>();
        for (WholesalePurchaseItem item : saved.getItems()) {
            productRepository.updateLastPurchase(item.getProduct().getWholesaleProductId(), item.getPurchaseRate(),
                supplier.getSupplierId());
            movements.add(WholesaleInventoryService.Movement.in(item.getProduct(), WholesaleInventoryTransactionType.PURCHASE,
                "PURCHASE", saved.getWholesalePurchaseId(), invoiceNumber, item.getQuantity(), item.getPurchaseRate(),
                saved.getInvoiceDate(), "Purchase from " + supplier.getSupplierName()));
        }
        inventoryService.post(movements, username);
        return toResponse(saved);
    }

    /**
     * Cancels a wholesale purchase: its quantities leave wholesale stock again (REVERSAL ledger rows) and the
     * purchase is kept as CANCELLED, so the ledger never loses its source document. Refused if the stock has
     * already been sold and negative stock is not allowed.
     */
    @Transactional
    public WholesalePurchaseResponse cancel(Integer id, String reason, String username) {
        WholesalePurchase purchase = purchaseRepository.findByIdForUpdate(id)
            .orElseThrow(() -> WholesaleException.notFound("Wholesale purchase #" + id + " not found"));
        if ("CANCELLED".equals(purchase.getStatus())) {
            throw WholesaleException.conflict("Purchase " + purchase.getInvoiceNumber() + " is already cancelled");
        }
        String why = reason == null || reason.isBlank() ? "Purchase cancelled" : reason.trim();
        LocalDate today = WholesaleDocumentNumberService.today();
        int reversed = inventoryService.reverseDocument("PURCHASE", id, purchase.getInvoiceNumber(), today,
            "Purchase cancelled: " + why, username);
        if (reversed == 0) {
            // Purchase recorded before the stock ledger existed: take its quantities out explicitly.
            List<WholesaleInventoryService.Movement> movements = new ArrayList<>();
            for (WholesalePurchaseItem item : purchase.getItems()) {
                movements.add(WholesaleInventoryService.Movement.out(item.getProduct(), WholesaleInventoryTransactionType.REVERSAL,
                    "PURCHASE", id, purchase.getInvoiceNumber(), item.getQuantity(), item.getPurchaseRate(), today,
                    "Purchase cancelled (recorded before the stock ledger): " + why));
            }
            inventoryService.post(movements, username);
        }
        purchase.setStatus("CANCELLED");
        purchase.setCancelledAt(LocalDateTime.now());
        purchase.setCancelledBy(username);
        purchase.setCancelReason(why);
        return toResponse(purchaseRepository.save(purchase));
    }

    private WholesaleProduct resolveProduct(WholesalePurchaseItemRequest line, int lineNo, Supplier supplier, Set<String> newCodes) {
        boolean hasExisting = line.wholesaleProductId() != null;
        boolean hasNew = line.newProduct() != null;
        if (hasExisting == hasNew) {
            throw WholesaleException.badRequest("Item " + lineNo + ": select an existing wholesale product or enter a new one (not both)");
        }
        if (hasExisting) {
            WholesaleProduct product = productRepository.findById(line.wholesaleProductId())
                .orElseThrow(() -> WholesaleException.badRequest("Item " + lineNo + ": wholesale product #"
                    + line.wholesaleProductId() + " not found"));
            if (!Boolean.TRUE.equals(product.getIsActive())) {
                throw WholesaleException.badRequest("Item " + lineNo + ": wholesale product '" + product.getProductName() + "' is inactive");
            }
            return product;
        }
        String code = WholesaleProductService.blankToNull(line.newProduct().productCode());
        if (code != null && !newCodes.add(code.toLowerCase(Locale.ROOT))) {
            throw WholesaleException.badRequest("Item " + lineNo + ": product code '" + code + "' is entered twice");
        }
        return productService.createForPurchase(line.newProduct(), line.purchaseRate(), line.gstPct(), supplier);
    }

    private WholesalePurchase findWithItems(Integer id) {
        return purchaseRepository.findByIdWithItems(id)
            .orElseThrow(() -> WholesaleException.notFound("Wholesale purchase #" + id + " not found"));
    }

    static WholesalePurchaseResponse toResponse(WholesalePurchase p) {
        Supplier s = p.getSupplier();
        return new WholesalePurchaseResponse(
            p.getWholesalePurchaseId(), s.getSupplierId(), s.getSupplierName(), s.getGstNumber(),
            p.getInvoiceNumber(), p.getInvoiceDate(), p.getInterstate(), p.getTaxableAmount(),
            p.getCgstAmount(), p.getSgstAmount(), p.getIgstAmount(), p.getGstAmount(), p.getTotalAmount(),
            p.getPaidAmount(), p.getPaymentStatus(), p.getPaymentDueDate(), p.getNotes(), p.getCreatedAt(),
            p.getItems().stream().map(WholesalePurchaseService::toItemResponse).toList(), p.getStatus(), p.getCreatedBy(),
            p.getCancelledAt(), p.getCancelledBy(), p.getCancelReason());
    }

    private static WholesalePurchaseItemResponse toItemResponse(WholesalePurchaseItem i) {
        WholesaleProduct product = i.getProduct();
        return new WholesalePurchaseItemResponse(
            i.getWholesalePurchaseItemId(), product.getWholesaleProductId(), product.getProductCode(),
            product.getProductName(), product.getUnit(), i.getHsnCode(), i.getQuantity(), i.getPurchaseRate(),
            i.getGstPct(), i.getTaxableAmount(), i.getCgstAmount(), i.getSgstAmount(), i.getIgstAmount(),
            i.getGstAmount(), i.getTotalAmount());
    }
}
