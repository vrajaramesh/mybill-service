package com.example.mybill.boutique.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Failure in the agent API, rendered by AgentExceptionHandler as {"code", "message", "details"}.
 * code is a stable machine-readable string the agent can branch on (e.g. INVALID_OPTION).
 */
public class AgentApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public AgentApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }

    public static AgentApiException notFound(String code, String message) {
        return new AgentApiException(HttpStatus.NOT_FOUND, code, message, null);
    }

    /** 422: the request is well-formed but breaks a business rule (inactive design, bad option, ...). */
    public static AgentApiException unprocessable(String code, String message, Map<String, Object> details) {
        return new AgentApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }

    public static AgentApiException conflict(String code, String message) {
        return new AgentApiException(HttpStatus.CONFLICT, code, message, null);
    }
}
