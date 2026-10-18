package com.example.mybill.boutique.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay webhook signature: X-Razorpay-Signature = hex(HMAC_SHA256(raw request body, webhook secret)).
 * Must be computed over the exact bytes received (before any JSON parsing) and compared in constant time.
 */
public final class RazorpaySignature {

    private RazorpaySignature() {}

    public static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean isValid(byte[] body, String signatureHeader, String secret) {
        if (signatureHeader == null || signatureHeader.isBlank() || secret == null || secret.isBlank()) return false;
        byte[] expected = sign(body, secret).getBytes(StandardCharsets.UTF_8);
        byte[] received = signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, received);
    }
}
