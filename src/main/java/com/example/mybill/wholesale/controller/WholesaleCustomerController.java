package com.example.mybill.wholesale.controller;

import com.example.mybill.wholesale.dto.WholesaleCustomerMatch;
import com.example.mybill.wholesale.dto.WholesaleCustomerOptions;
import com.example.mybill.wholesale.dto.WholesaleCustomerRequest;
import com.example.mybill.wholesale.dto.WholesaleCustomerResponse;
import com.example.mybill.wholesale.service.WholesaleCustomerService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wholesale/customers")
public class WholesaleCustomerController {

    @Autowired
    private WholesaleCustomerService customerService;

    /** List / picker search. e.g. ?q=ravi&type=WHOLESALE&activeOnly=true&limit=20 */
    @GetMapping
    public List<WholesaleCustomerResponse> search(@RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String type,
                                                  @RequestParam(defaultValue = "false") boolean activeOnly,
                                                  @RequestParam(required = false) Integer limit) {
        return customerService.search(q, type, activeOnly, limit);
    }

    @GetMapping("/options")
    public WholesaleCustomerOptions options() {
        return customerService.options();
    }

    @GetMapping("/duplicate-check")
    public List<WholesaleCustomerMatch> duplicateCheck(@RequestParam(required = false) String gstNumber,
                                                       @RequestParam(required = false) String phone,
                                                       @RequestParam(required = false) String customerName,
                                                       @RequestParam(required = false) Integer excludeId) {
        return customerService.findDuplicates(gstNumber, phone, customerName, excludeId);
    }

    @GetMapping("/{id}")
    public WholesaleCustomerResponse get(@PathVariable Integer id) {
        return customerService.get(id);
    }

    @PostMapping
    public ResponseEntity<WholesaleCustomerResponse> create(@Valid @RequestBody WholesaleCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.create(request));
    }

    @PutMapping("/{id}")
    public WholesaleCustomerResponse update(@PathVariable Integer id, @Valid @RequestBody WholesaleCustomerRequest request) {
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        customerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
