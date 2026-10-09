package com.example.mybill.wholesale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression: the existing retail purchase / billing / product APIs behave exactly as before
 * while the wholesale tables exist in the same firm schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailPurchaseRegressionTest extends AbstractFirmIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private FirmCtx firm;
    private int supplierId;

    @BeforeAll
    void setUp() throws Exception {
        firm = registerFirm("retailreg");
        supplierId = insertSupplier(firm, "Retail Supplier", "36AAAAA0000A1Z5");
        // Make sure the wholesale tables exist for this firm, as they will in production.
        mvc.perform(get("/api/wholesale/products").header("Authorization", firm.bearer())).andExpect(status().isOk());
        assertThat(tableExists(firm, "wholesale_products")).isTrue();
    }

    private String retailPurchaseJson(String invoice, int productId, String qty) {
        return """
            {"supplier":{"supplierId":%d},"invoiceNumber":"%s","invoiceDate":"%s","totalAmount":0,
             "purchaseItems":[{"product":{"productId":%d},"quantity":%s,"unitPrice":100,"gst":5}]}
            """.formatted(supplierId, invoice, LocalDate.now(), productId, qty);
    }

    @Test
    void retailPurchaseCreateUpdateDelete_movesRetailStockExactlyAsBefore() throws Exception {
        insertRetailProduct(firm, 1001, "Retail Silk", "10");

        String body = mvc.perform(post("/api/purchases").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(retailPurchaseJson("R-INV-1", 1001, "5")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalAmount").value(500.0))
            .andExpect(jsonPath("$.gst").value(25.0))
            .andExpect(jsonPath("$.finalAmount").value(525.0))
            .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
            .andExpect(jsonPath("$.supplier.supplierId").value(supplierId))
            .andReturn().getResponse().getContentAsString();
        int purchaseId = json.readTree(body).get("purchaseId").asInt();
        assertThat(retailStock(firm, 1001)).isEqualByComparingTo("15");

        mvc.perform(put("/api/purchases/" + purchaseId).header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(retailPurchaseJson("R-INV-1", 1001, "3")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.finalAmount").value(315.0));
        assertThat(retailStock(firm, 1001)).isEqualByComparingTo("13");

        mvc.perform(get("/api/purchases").header("Authorization", firm.bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.purchaseId == " + purchaseId + ")].invoiceNumber").value("R-INV-1"));

        mvc.perform(delete("/api/purchases/" + purchaseId).header("Authorization", firm.bearer()))
            .andExpect(status().isNoContent());
        assertThat(retailStock(firm, 1001)).isEqualByComparingTo("10");

        assertThat(count(firm, "wholesale_products")).isZero();
        assertThat(count(firm, "wholesale_purchases")).isZero();
    }

    @Test
    void retailPurchasePayment_updatesStatusAsBefore() throws Exception {
        insertRetailProduct(firm, 1002, "Retail Cotton", "0");
        String body = mvc.perform(post("/api/purchases").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(retailPurchaseJson("R-INV-2", 1002, "2")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int purchaseId = json.readTree(body).get("purchaseId").asInt();

        mvc.perform(post("/api/purchases/" + purchaseId + "/payments").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentDate\":\"" + LocalDate.now() + "\",\"amount\":100,\"paymentMethod\":\"CASH\"}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/purchases/" + purchaseId).header("Authorization", firm.bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paidAmount").value(100.0))
            .andExpect(jsonPath("$.paymentStatus").value("PARTIAL"));
    }

    @Test
    void retailBill_deductsRetailStockAndUsesRetailNumbering() throws Exception {
        insertRetailProduct(firm, 1003, "Retail Georgette", "20");
        String billJson = """
            {"paymentMethod":"CASH","billItems":[{"product":{"productId":1003},"quantity":2,"unitPrice":150,"discountPct":0,"gstPct":5}]}
            """;
        String body = mvc.perform(post("/api/bills").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(billJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalAmount").value(300.0))
            .andReturn().getResponse().getContentAsString();
        JsonNode bill = json.readTree(body);
        assertThat(bill.get("billNumber").asText()).matches("BILL-\\d{4}-\\d{4}");
        assertThat(retailStock(firm, 1003)).isEqualByComparingTo(new BigDecimal("18"));
        assertThat(count(firm, "wholesale_products")).isZero();
    }

    @Test
    void retailProductSelectionList_keepsItsShape() throws Exception {
        insertRetailProduct(firm, 1004, "Retail Linen", "7");
        mvc.perform(get("/api/products").header("Authorization", firm.bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.productId == 1004)].productName").value("Retail Linen"))
            .andExpect(jsonPath("$[?(@.productId == 1004)].sellingPrice").value(150.0))
            .andExpect(jsonPath("$[?(@.productId == 1004)].stockQuantity").value(7.0));
    }
}
