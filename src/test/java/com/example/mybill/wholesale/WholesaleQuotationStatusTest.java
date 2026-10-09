package com.example.mybill.wholesale;

import com.example.mybill.wholesale.entity.WholesaleQuotationStatus;
import org.junit.jupiter.api.Test;

import static com.example.mybill.wholesale.entity.WholesaleQuotationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class WholesaleQuotationStatusTest {

    @Test
    void draft_canBeIssuedOrCancelled_andIsTheOnlyEditableStatus() {
        assertThat(DRAFT.allowedNext()).containsExactlyInAnyOrder(ISSUED, CANCELLED);
        for (WholesaleQuotationStatus s : values()) {
            assertThat(s.isEditable()).isEqualTo(s == DRAFT);
        }
    }

    @Test
    void issued_canBeAcceptedRejectedExpiredConvertedOrCancelled_butNotReissued() {
        assertThat(ISSUED.allowedNext()).containsExactlyInAnyOrder(ACCEPTED, REJECTED, EXPIRED, CONVERTED, CANCELLED);
        assertThat(ISSUED.canMoveTo(DRAFT)).isFalse();
    }

    @Test
    void onlyIssuedOrAcceptedQuotationsAreConvertible() {
        for (WholesaleQuotationStatus s : values()) {
            assertThat(s.isConvertible()).as(s.name()).isEqualTo(s == ISSUED || s == ACCEPTED);
        }
        assertThat(CONVERTED.canRevertConversion()).isTrue();
        assertThat(CONVERTED.canMoveTo(ACCEPTED)).as("users cannot un-convert").isFalse();
    }

    @Test
    void accepted_canOnlyBeConvertedOrCancelled() {
        assertThat(ACCEPTED.allowedNext()).containsExactlyInAnyOrder(CONVERTED, CANCELLED);
    }

    @Test
    void finalStatuses_haveNoNextStatus() {
        for (WholesaleQuotationStatus s : new WholesaleQuotationStatus[]{REJECTED, EXPIRED, CONVERTED, CANCELLED}) {
            assertThat(s.allowedNext()).as(s.name()).isEmpty();
        }
    }
}
