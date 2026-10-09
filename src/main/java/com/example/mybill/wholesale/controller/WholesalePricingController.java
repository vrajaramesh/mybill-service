package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.dto.WholesalePriceQuoteResponse;
import com.example.mybill.wholesale.dto.WholesalePriceRuleRequest;
import com.example.mybill.wholesale.dto.WholesalePriceRuleResponse;
import com.example.mybill.wholesale.service.WholesalePricingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/wholesale/products/{productId}")
public class WholesalePricingController {

    @Autowired
    private WholesalePricingService pricingService;

    @GetMapping("/price-rules")
    public List<WholesalePriceRuleResponse> listRules(@PathVariable Integer productId,
                                                      @RequestParam(defaultValue = "true") boolean includeInactive) {
        return pricingService.listRules(productId, includeInactive);
    }

    @PostMapping("/price-rules")
    public ResponseEntity<WholesalePriceRuleResponse> createRule(@PathVariable Integer productId,
                                                                 @Valid @RequestBody WholesalePriceRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pricingService.createRule(productId, request));
    }

    @PutMapping("/price-rules/{ruleId}")
    public WholesalePriceRuleResponse updateRule(@PathVariable Integer productId, @PathVariable Integer ruleId,
                                                 @Valid @RequestBody WholesalePriceRuleRequest request) {
        return pricingService.updateRule(productId, ruleId, request);
    }

    @DeleteMapping("/price-rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(@PathVariable Integer productId, @PathVariable Integer ruleId) {
        pricingService.deleteRule(productId, ruleId);
        return ResponseEntity.noContent().build();
    }

    /** e.g. GET /api/wholesale/products/12/price?quantity=50 (optional: date=2026-11-01, interstate=true) */
    @GetMapping("/price")
    public WholesalePriceQuoteResponse price(@PathVariable Integer productId,
                                             @RequestParam BigDecimal quantity,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(defaultValue = "false") boolean interstate) {
        return pricingService.quote(productId, quantity, date, interstate);
    }
}
