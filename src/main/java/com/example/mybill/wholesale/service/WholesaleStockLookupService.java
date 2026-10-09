package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Read-only wholesale stock facts for the customer messaging assistant. Never changes stock. */
@Service
public class WholesaleStockLookupService {

    /**
     * @param availableQuantity only when the business chose to share exact quantities, otherwise null
     * @param enoughForRequested null when the customer did not ask for a quantity
     */
    public record StockInfo(boolean inStock, BigDecimal availableQuantity, Boolean enoughForRequested, String unit) {}

    @Autowired private WholesaleProductRepository productRepository;

    @Transactional(readOnly = true)
    public StockInfo lookup(Integer productId, BigDecimal requestedQuantity, boolean shareQuantity) {
        return productRepository.findById(productId).map(p -> {
            BigDecimal available = p.getAvailableQuantity() == null ? BigDecimal.ZERO : p.getAvailableQuantity();
            Boolean enough = requestedQuantity == null ? null : available.compareTo(requestedQuantity) >= 0;
            return new StockInfo(available.signum() > 0, shareQuantity ? WholesalePriceLookupService.plain(available) : null, enough, p.getUnit());
        }).orElse(new StockInfo(false, null, requestedQuantity == null ? null : false, null));
    }
}
