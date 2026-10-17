package com.example.mybill.boutique.exception;

import com.example.mybill.boutique.dto.AgentError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error responses for the agent API only (basePackages-scoped, so retail and wholesale error shapes are unchanged).
 * Shape: {"code": "VALIDATION_FAILED", "message": "...", "details": {...}}.
 */
@RestControllerAdvice(basePackages = "com.example.mybill.boutique.controller")
public class AgentExceptionHandler {

    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<AgentError> handleAgent(AgentApiException e) {
        return ResponseEntity.status(e.getStatus()).body(new AgentError(e.getCode(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
            .forEach(fe -> fieldErrors.putIfAbsent(snakeCase(fe.getField()), fe.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors()
            .forEach(ge -> fieldErrors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        String first = fieldErrors.isEmpty() ? "Invalid request" : String.valueOf(fieldErrors.values().iterator().next());
        return ResponseEntity.badRequest().body(new AgentError("VALIDATION_FAILED", first, Map.of("fields", fieldErrors)));
    }

    /** Field paths in the API's JSON naming: "delivery.addressLine1" -> "delivery.address_line1". */
    static String snakeCase(String path) {
        return path.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<AgentError> handleParamValidation(HandlerMethodValidationException e) {
        return ResponseEntity.badRequest().body(new AgentError("VALIDATION_FAILED", "Invalid request parameter", Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new AgentError("MALFORMED_REQUEST", "Malformed request body", Map.of()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<AgentError> handleBadParam(Exception e) {
        String name = e instanceof MissingServletRequestParameterException m ? m.getParameterName()
            : ((MethodArgumentTypeMismatchException) e).getName();
        return ResponseEntity.badRequest().body(
            new AgentError("VALIDATION_FAILED", "Parameter '" + name + "' is missing or invalid", Map.of("parameter", name)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<AgentError> handleIntegrity(DataIntegrityViolationException e) {
        String detail = String.valueOf(e.getMostSpecificCause().getMessage());
        String message = "The change conflicts with existing data";
        if (detail.contains("uq_customers_phone_e164")) message = "Another customer already has this phone number";
        else if (detail.contains("uq_customers_instagram_user_id")) message = "Another customer already has this Instagram account";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new AgentError("CONFLICT", message, Map.of()));
    }
}
