package com.example.mybill.wholesale;

import com.example.mybill.wholesale.entity.WholesaleSalesDocumentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.example.mybill.wholesale.entity.WholesaleSalesDocumentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WholesaleSalesDocumentStatusTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void forPayment_followsAmountPaidAgainstTotal() {
        assertThat(forPayment(d("0"), d("3697.00"))).isEqualTo(ISSUED);
        assertThat(forPayment(d("1000.00"), d("3697.00"))).isEqualTo(PARTIALLY_PAID);
        assertThat(forPayment(d("3697.00"), d("3697.00"))).isEqualTo(PAID);
        assertThat(forPayment(d("3697"), d("3697.00"))).as("scale does not matter").isEqualTo(PAID);
    }

    @Test
    void forPayment_rejectsOverpaymentAndNegative() {
        assertThatThrownBy(() -> forPayment(d("3697.01"), d("3697.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> forPayment(d("-1"), d("100"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyPaymentStatus_staysInStep() {
        assertThat(paymentStatusFor(d("0"), d("100"))).isEqualTo("UNPAID");
        assertThat(paymentStatusFor(d("40"), d("100"))).isEqualTo("PARTIAL");
        assertThat(paymentStatusFor(d("100"), d("100"))).isEqualTo("PAID");
    }

    @Test
    void draft_isTheOnlyEditableStatus_andCannotTakePayments() {
        for (WholesaleSalesDocumentStatus s : values()) {
            assertThat(s.isEditable()).as(s.name()).isEqualTo(s == DRAFT);
        }
        assertThat(DRAFT.acceptsPayments()).isFalse();
        assertThat(DRAFT.isActive()).isFalse();
    }

    @Test
    void paymentsAllowedOnlyWhileSomethingIsDue() {
        assertThat(ISSUED.acceptsPayments()).isTrue();
        assertThat(PARTIALLY_PAID.acceptsPayments()).isTrue();
        assertThat(PAID.acceptsPayments()).isFalse();
        assertThat(CANCELLED.acceptsPayments()).isFalse();
    }

    @Test
    void transitions() {
        assertThat(DRAFT.allowedNext()).containsExactlyInAnyOrder(ISSUED, PARTIALLY_PAID, PAID, CANCELLED);
        assertThat(ISSUED.canMoveTo(DRAFT)).as("issued documents never go back to draft").isFalse();
        assertThat(PAID.canMoveTo(PARTIALLY_PAID)).as("voiding a payment").isTrue();
        assertThat(PAID.canMoveTo(DRAFT)).isFalse();
        assertThat(CANCELLED.allowedNext()).isEmpty();
        for (WholesaleSalesDocumentStatus s : new WholesaleSalesDocumentStatus[]{ISSUED, PARTIALLY_PAID, PAID}) {
            assertThat(s.isActive()).as(s.name()).isTrue();
            assertThat(s.canMoveTo(CANCELLED)).as(s.name()).isTrue();
        }
    }
}
