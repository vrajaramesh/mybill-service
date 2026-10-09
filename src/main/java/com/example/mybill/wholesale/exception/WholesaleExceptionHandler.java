package com.example.mybill.wholesale.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error responses for wholesale controllers only (basePackages-scoped, so retail error behaviour is unchanged).
 * Shape: {"message": "...", "fieldErrors": {"items[0].quantity": "..."}} — the UI already reads err.error.message.
 */
@RestControllerAdvice(basePackages = "com.example.mybill.wholesale.controller")
public class WholesaleExceptionHandler {

    @ExceptionHandler(WholesaleException.class)
    public ResponseEntity<Map<String, Object>> handleWholesale(WholesaleException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
            .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors()
            .forEach(ge -> fieldErrors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        String first = fieldErrors.isEmpty() ? "Invalid request" : fieldErrors.values().iterator().next();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", first);
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Malformed request body"));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadParam(Exception e) {
        String name = e instanceof MissingServletRequestParameterException m ? m.getParameterName()
            : ((MethodArgumentTypeMismatchException) e).getName();
        String message = e instanceof MissingServletRequestParameterException
            ? "Parameter '" + name + "' is required" : "Parameter '" + name + "' has an invalid value";
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause().getMessage();
        String message = "The change conflicts with existing data";
        if (detail != null && detail.contains("uq_wholesale_purchases_supplier_invoice")) {
            message = "This supplier invoice number is already recorded as a wholesale purchase";
        } else if (detail != null && detail.contains("uq_wholesale_products_code")) {
            message = "Product code is already used by another wholesale product";
        } else if (detail != null && detail.contains("uq_wholesale_customers_gst")) {
            message = "This GST number is already registered for another wholesale customer";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message));
    }
}
