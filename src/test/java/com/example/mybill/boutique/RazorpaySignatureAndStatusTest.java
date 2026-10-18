package com.example.mybill.boutique;

import com.example.mybill.boutique.entity.BoutiqueOrderStatus;
import com.example.mybill.boutique.payment.RazorpaySignature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.example.mybill.boutique.entity.BoutiqueOrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignatureAndStatusTest {

    private static final String SECRET = "test_webhook_secret";
    private static final byte[] BODY = "{\"event\":\"payment_link.paid\",\"entity\":\"event\"}".getBytes(StandardCharsets.UTF_8);
    // printf '%s' '{"event":"payment_link.paid","entity":"event"}' | openssl dgst -sha256 -hmac test_webhook_secret
    private static final String KNOWN = "fe1d07b9de35882f0e27531c898d6e6967e5e253fdcabd56f83819aba7b6f091";

    @Test
    void signatureMatchesOpensslAndValidates() {
        String sig = RazorpaySignature.sign(BODY, SECRET);
        assertThat(sig).isEqualTo(KNOWN);
        assertThat(RazorpaySignature.isValid(BODY, sig, SECRET)).isTrue();
        assertThat(RazorpaySignature.isValid(BODY, sig.toUpperCase(), SECRET)).isTrue();
    }

    @Test
    void invalidSignaturesAreRejected() {
        String sig = RazorpaySignature.sign(BODY, SECRET);
        assertThat(RazorpaySignature.isValid(BODY, null, SECRET)).isFalse();
        assertThat(RazorpaySignature.isValid(BODY, "", SECRET)).isFalse();
        assertThat(RazorpaySignature.isValid(BODY, sig, "other_secret")).isFalse();
        assertThat(RazorpaySignature.isValid("{\"tampered\":1}".getBytes(StandardCharsets.UTF_8), sig, SECRET)).isFalse();
        assertThat(RazorpaySignature.isValid(BODY, sig, "")).isFalse();
    }

    @Test
    void statusMovesForwardOnly() {
        assertThat(DRAFT.canMoveTo(PAYMENT_PENDING)).isTrue();
        assertThat(PAYMENT_PENDING.canMoveTo(PAID)).isTrue();
        assertThat(DRAFT.canMoveTo(PAID)).isTrue();
        assertThat(PAID.canMoveTo(IN_STITCHING)).isTrue();
        assertThat(PAID.canMoveTo(PAYMENT_PENDING)).isFalse();   // never backwards
        assertThat(PAID.canMoveTo(PAID)).isFalse();
        assertThat(PAID.canMoveTo(CANCELLED)).isFalse();         // cancel only before payment
        assertThat(PAYMENT_PENDING.canMoveTo(CANCELLED)).isTrue();
        assertThat(CANCELLED.canMoveTo(PAID)).isFalse();
        assertThat(DELIVERED.canMoveTo(CANCELLED)).isFalse();
        assertThat(PAYMENT_PENDING.isPayable()).isTrue();
        assertThat(PAID.isPayable()).isFalse();
        assertThat(CANCELLED.isPayable()).isFalse();
    }
}
