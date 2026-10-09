package com.example.mybill.wholesale.service;

import com.example.mybill.service.Supplier;
import com.example.mybill.service.SupplierRepository;
import com.example.mybill.wholesale.dto.WholesaleProductImageRequest;
import com.example.mybill.wholesale.dto.WholesaleProductImageResponse;
import com.example.mybill.wholesale.dto.WholesaleProductRequest;
import com.example.mybill.wholesale.dto.WholesaleProductResponse;
import com.example.mybill.wholesale.entity.WholesaleProduct;
import com.example.mybill.wholesale.entity.WholesaleProductImage;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleProductImageRepository;
import com.example.mybill.wholesale.repository.WholesaleProductPriceRuleRepository;
import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Wholesale product master. Never reads or writes the retail products table. */
@Service
public class WholesaleProductService {

    @Autowired private WholesaleProductRepository productRepository;
    @Autowired private WholesaleProductImageRepository imageRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private WholesaleProductPriceRuleRepository priceRuleRepository;
    @Autowired private WholesaleInventoryService inventoryService;

    /**
     * @param q           optional search over name, code, type, material and HSN (case-insensitive)
     * @param inStockOnly only products with available quantity > 0
     */
    @Transactional(readOnly = true)
    public List<WholesaleProductResponse> list(boolean activeOnly, String q, boolean inStockOnly) {
        List<WholesaleProduct> products = activeOnly
            ? productRepository.findByIsActiveTrueOrderByProductNameAsc()
            : productRepository.findAllByOrderByProductNameAsc();
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<Integer, Integer> ruleCounts = activeRuleCounts();
        return products.stream()
            .filter(p -> !inStockOnly || p.getAvailableQuantity().signum() > 0)
            .filter(p -> term.isEmpty() || matches(p, term))
            .map(p -> toResponse(p, ruleCounts.getOrDefault(p.getWholesaleProductId(), 0)))
            .toList();
    }

    @Transactional(readOnly = true)
    public WholesaleProductResponse get(Integer id) {
        return toResponse(find(id), priceRuleRepository.findByProduct_WholesaleProductIdAndIsActiveTrue(id).size());
    }

    @Transactional
    public WholesaleProductResponse create(WholesaleProductRequest request) {
        WholesaleProduct product = new WholesaleProduct();
        apply(product, request);
        product.setSupplier(request.supplierId() != null ? findSupplier(request.supplierId()) : null);
        assertCodeAvailable(product.getProductCode(), null);
        return toResponse(productRepository.save(product), 0);
    }

    @Transactional
    public WholesaleProductResponse update(Integer id, WholesaleProductRequest request) {
        WholesaleProduct product = find(id);
        apply(product, request);
        if (request.supplierId() != null) product.setSupplier(findSupplier(request.supplierId()));
        assertCodeAvailable(product.getProductCode(), id);
        WholesaleProduct saved = productRepository.save(product);
        return toResponse(saved, priceRuleRepository.findByProduct_WholesaleProductIdAndIsActiveTrue(id).size());
    }

    /** Hard delete is only allowed for products never purchased; otherwise deactivate (keeps purchase history intact). */
    @Transactional
    public void delete(Integer id) {
        WholesaleProduct product = find(id);
        if (productRepository.hasPurchases(id) || productRepository.hasQuotationItems(id)
                || productRepository.hasSalesDocumentItems(id) || inventoryService.hasMovements(id)) {
            throw WholesaleException.conflict("'" + product.getProductName()
                + "' has wholesale documents or stock history and cannot be deleted. Mark it inactive instead.");
        }
        productRepository.delete(product);
    }

    @Transactional
    public WholesaleProductImageResponse addImage(Integer productId, WholesaleProductImageRequest request) {
        WholesaleProduct product = find(productId);
        WholesaleProductImage image = new WholesaleProductImage();
        image.setProduct(product);
        image.setImageUrl(request.imageUrl().trim());
        image.setPublicId(blankToNull(request.publicId()));
        image.setSortOrder(request.sortOrder() != null ? request.sortOrder() : product.getImages().size());
        product.getImages().add(image);
        return toImageResponse(imageRepository.save(image));
    }

    @Transactional
    public void deleteImage(Integer productId, Integer imageId) {
        WholesaleProductImage image = imageRepository.findByImageIdAndProduct_WholesaleProductId(imageId, productId)
            .orElseThrow(() -> WholesaleException.notFound("Image not found for this wholesale product"));
        image.getProduct().getImages().remove(image);
        imageRepository.delete(image);
    }

    /**
     * Builds and saves a wholesale product entered inline on a wholesale purchase line.
     * Stock starts at 0; the purchase adds the purchased quantity.
     */
    @Transactional
    WholesaleProduct createForPurchase(WholesaleProductRequest request, BigDecimal rate, BigDecimal gstPct, Supplier supplier) {
        WholesaleProduct product = new WholesaleProduct();
        apply(product, request);
        product.setPurchaseRate(rate);
        product.setGstPct(gstPct);
        product.setSupplier(supplier);
        assertCodeAvailable(product.getProductCode(), null);
        return productRepository.save(product);
    }

    WholesaleProduct find(Integer id) {
        return productRepository.findById(id)
            .orElseThrow(() -> WholesaleException.notFound("Wholesale product #" + id + " not found"));
    }

    private Supplier findSupplier(Integer supplierId) {
        return supplierRepository.findById(supplierId)
            .orElseThrow(() -> WholesaleException.badRequest("Supplier #" + supplierId + " not found"));
    }

    private void assertCodeAvailable(String code, Integer exceptId) {
        if (code == null) return;
        boolean taken = exceptId == null
            ? productRepository.existsByProductCodeIgnoreCase(code)
            : productRepository.existsByProductCodeIgnoreCaseAndWholesaleProductIdNot(code, exceptId);
        if (taken) throw WholesaleException.conflict("Product code '" + code + "' is already used by another wholesale product");
    }

    private static void apply(WholesaleProduct product, WholesaleProductRequest r) {
        product.setProductCode(blankToNull(r.productCode()));
        product.setProductName(r.productName().trim());
        product.setProductType(blankToNull(r.productType()));
        product.setHsnCode(blankToNull(r.hsnCode()));
        product.setDescription(blankToNull(r.description()));
        product.setMaterial(blankToNull(r.material()));
        product.setUnit(r.unit().trim());
        if (r.purchaseRate() != null) product.setPurchaseRate(r.purchaseRate());
        if (r.gstPct() != null) product.setGstPct(r.gstPct());
        if (r.isActive() != null) product.setIsActive(r.isActive());
        product.setFabricType(blankToNull(r.fabricType()));
        product.setAvailableColors(blankToNull(r.availableColors()));
        product.setAvailableDesigns(blankToNull(r.availableDesigns()));
        product.setSpecifications(blankToNull(r.specifications()));
    }

    private Map<Integer, Integer> activeRuleCounts() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Object[] row : priceRuleRepository.countActiveByProduct()) {
            counts.put((Integer) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    private static boolean matches(WholesaleProduct p, String term) {
        return contains(p.getProductName(), term) || contains(p.getProductCode(), term)
            || contains(p.getProductType(), term) || contains(p.getMaterial(), term) || contains(p.getHsnCode(), term)
            || contains(p.getFabricType(), term);
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static WholesaleProductResponse toResponse(WholesaleProduct p, int activePriceRuleCount) {
        Supplier s = p.getSupplier();
        return new WholesaleProductResponse(
            p.getWholesaleProductId(), p.getProductCode(), p.getProductName(), p.getProductType(),
            p.getHsnCode(), p.getDescription(), p.getMaterial(), p.getUnit(), p.getAvailableQuantity(),
            p.getPurchaseRate(), p.getGstPct(), s != null ? s.getSupplierId() : null,
            s != null ? s.getSupplierName() : null, p.getIsActive(), p.getCreatedAt(), p.getUpdatedAt(),
            p.getImages().stream().map(WholesaleProductService::toImageResponse).toList(),
            activePriceRuleCount, p.getFabricType(), p.getAvailableColors(), p.getAvailableDesigns(), p.getSpecifications());
    }

    private static WholesaleProductImageResponse toImageResponse(WholesaleProductImage i) {
        return new WholesaleProductImageResponse(i.getImageId(), i.getImageUrl(), i.getPublicId(), i.getSortOrder(), i.getCreatedAt());
    }
}
