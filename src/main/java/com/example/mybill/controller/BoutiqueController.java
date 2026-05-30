package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/boutique")
public class BoutiqueController {

    @Autowired
    private BoutiqueService boutiqueService;

    // ── Orders ────────────────────────────────────────────

    @GetMapping("/orders")
    public List<StitchingOrder> getOrders(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return boutiqueService.getOrders(status, from, to);
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<StitchingOrder> getOrder(@PathVariable Integer id) {
        return boutiqueService.getOrderById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody StitchingOrder order) {
        try {
            return ResponseEntity.ok(boutiqueService.createOrder(order));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/orders/{id}")
    public ResponseEntity<?> updateOrder(@PathVariable Integer id, @RequestBody StitchingOrder order) {
        StitchingOrder updated = boutiqueService.updateOrder(id, order);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    @PatchMapping("/orders/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Integer id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        StitchingOrder updated = boutiqueService.updateStatus(id, status);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Integer id) {
        boutiqueService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/orders/{id}/payments")
    public ResponseEntity<?> addPayment(@PathVariable Integer id, @RequestBody BoutiquePayment payment) {
        StitchingOrder updated = boutiqueService.addPayment(id, payment);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    @PostMapping("/orders/{id}/extra-charges")
    public ResponseEntity<?> addExtraCharge(@PathVariable Integer id, @RequestBody ExtraCharge charge) {
        StitchingOrder updated = boutiqueService.addExtraCharge(id, charge);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    // ── Item Images ───────────────────────────────────────

    @GetMapping("/items/{itemId}/images")
    public List<BoutiqueOrderImage> getItemImages(@PathVariable Integer itemId) {
        return boutiqueService.getItemImages(itemId);
    }

    @PostMapping("/items/{itemId}/images")
    public ResponseEntity<?> addItemImage(@PathVariable Integer itemId, @RequestBody Map<String, String> body) {
        BoutiqueOrderImage img = boutiqueService.addItemImage(itemId, body.get("imageUrl"), body.get("publicId"));
        return img != null ? ResponseEntity.ok(img) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/items/{itemId}/images/{imageId}")
    public ResponseEntity<Void> deleteItemImage(@PathVariable Integer itemId, @PathVariable Integer imageId) {
        boutiqueService.deleteItemImage(imageId);
        return ResponseEntity.noContent().build();
    }

    // ── Measurements ──────────────────────────────────────

    @GetMapping("/measurements")
    public List<CustomerMeasurement> getMeasurements(@RequestParam Integer customerId) {
        return boutiqueService.getMeasurements(customerId);
    }

    @PostMapping("/measurements")
    public CustomerMeasurement saveMeasurement(@RequestBody CustomerMeasurement m) {
        return boutiqueService.saveMeasurement(m);
    }

    @PutMapping("/measurements/{id}")
    public CustomerMeasurement updateMeasurement(@PathVariable Integer id, @RequestBody CustomerMeasurement m) {
        m.setMeasurementId(id);
        return boutiqueService.saveMeasurement(m);
    }

    @DeleteMapping("/measurements/{id}")
    public ResponseEntity<Void> deleteMeasurement(@PathVariable Integer id) {
        boutiqueService.deleteMeasurement(id);
        return ResponseEntity.noContent().build();
    }

    // ── Reports ───────────────────────────────────────────

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        return boutiqueService.getSummary();
    }
}
