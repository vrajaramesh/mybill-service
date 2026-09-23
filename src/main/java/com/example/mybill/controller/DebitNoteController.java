package com.example.mybill.controller;

import com.example.mybill.dto.DebitNote;
import com.example.mybill.service.DebitNoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/debit-notes")
public class DebitNoteController {
    @Autowired private DebitNoteService debitNoteService;

    @GetMapping("/purchase/{purchaseId}")
    public List<DebitNote> getByPurchase(@PathVariable Integer purchaseId) {
        return debitNoteService.getByPurchase(purchaseId);
    }

    @PostMapping("/purchase/{purchaseId}")
    public DebitNote create(@PathVariable Integer purchaseId, @RequestBody DebitNote note) {
        return debitNoteService.create(purchaseId, note);
    }
}