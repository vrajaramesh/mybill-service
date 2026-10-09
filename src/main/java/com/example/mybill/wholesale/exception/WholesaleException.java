package com.example.mybill.wholesale.exception;

import org.springframework.http.HttpStatus;

/** Business-rule failure in the wholesale module, mapped to an HTTP status by WholesaleExceptionHandler. */
public class WholesaleException extends RuntimeException {

    private final HttpStatus status;

    public WholesaleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }

    public static WholesaleException notFound(String message) { return new WholesaleException(HttpStatus.NOT_FOUND, message); }
    public static WholesaleException badRequest(String message) { return new WholesaleException(HttpStatus.BAD_REQUEST, message); }
    public static WholesaleException conflict(String message) { return new WholesaleException(HttpStatus.CONFLICT, message); }
}
