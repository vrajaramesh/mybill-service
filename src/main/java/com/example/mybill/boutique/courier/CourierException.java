package com.example.mybill.boutique.courier;

/** A courier (Delhivery) call failed; the message is shown to staff as is. */
public class CourierException extends RuntimeException {
    public CourierException(String message) {
        super(message);
    }
}
