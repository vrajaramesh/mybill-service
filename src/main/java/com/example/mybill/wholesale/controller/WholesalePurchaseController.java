package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.config.WholesaleAccess;
import com.example.mybill.wholesale.dto.WholesaleCancelRequest;
import com.example.mybill.wholesale.dto.WholesalePurchaseRequest;
import com.example.mybill.wholesale.dto.WholesalePurchaseResponse;
import com.example.mybill.wholesale.service.WholesalePurchaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wholesale/purchases")
public class WholesalePurchaseController {

    @Autowired
    private WholesalePurchaseService purchaseService;

    @Autowired
    private WholesaleAccess access;

    @GetMapping
    public List<WholesalePurchaseResponse> list() {
        return purchaseService.list();
    }

    @GetMapping("/{id}")
    public WholesalePurchaseResponse get(@PathVariable Integer id) {
        return purchaseService.get(id);
    }

    @PostMapping
    public ResponseEntity<WholesalePurchaseResponse> create(@Valid @RequestBody WholesalePurchaseRequest request,
                                                            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseService.create(request, access.username(http)));
    }

    /** Cancels the purchase (stock reversed through the ledger); the record is kept. Body: {"reason": "…"}. */
    @PostMapping("/{id}/cancel")
    public WholesalePurchaseResponse cancel(@PathVariable Integer id, @Valid @RequestBody WholesaleCancelRequest request,
                                            HttpServletRequest http) {
        return purchaseService.cancel(id, request.reason(), access.username(http));
    }

    /** Kept for older clients: same as cancel with a default reason. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id, HttpServletRequest http) {
        purchaseService.cancel(id, "Deleted", access.username(http));
        return ResponseEntity.noContent().build();
    }
}
