package com.example.mybill.wholesale;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Proves wholesale purchases never reach retail products, retail stock, retail selection lists or retail billing. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WholesaleRetailIsolationTest extends AbstractFirmIntegrationTest {

    private static final String WS_NAME = "WSONLY Banarasi Bale";

    private final ObjectMapper json = new ObjectMapper();
    private FirmCtx firm;
    private int supplierId;
    private int wholesaleProductId;

    @BeforeAll
    void setUp() throws Exception {
        firm = registerFirm("wsiso");
        supplierId = insertSupplier(firm, "Varanasi Weavers", null);
        // Retail product deliberately shares id 1 with the first wholesale product (separate id spaces).
        insertRetailProduct(firm, 1, "Retail Product One", "50");
        insertRetailProduct(firm, 1000, "Retail Product Thousand", "8");

        String before = retailSnapshot(firm);
        String body = mvc.perform(post("/api/wholesale/purchases").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"supplierId":%d,"invoiceNumber":"ISO-1","invoiceDate":"%s",
                     "items":[{"newProduct":{"productName":"%s","unit":"Pieces","hsnCode":"5007"},
                               "quantity":40,"purchaseRate":900,"gstPct":5}]}
                    """.formatted(supplierId, LocalDate.now(), WS_NAME)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        wholesaleProductId = json.readTree(body).get("items").get(0).get("wholesaleProductId").asInt();

        assertThat(retailSnapshot(firm)).as("wholesale purchase must not touch any retail table").isEqualTo(before);
    }

    @Test
    void wholesaleProductsNeverAppearInRetailProductSelection() throws Exception {
        mvc.perform(get("/api/products").header("Authorization", firm.bearer()))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString(WS_NAME))))
            .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(get("/api/products/" + wholesaleProductId).header("Authorization", firm.bearer()))
            .andExpect(jsonPath("$.productName").value("Retail Product One"));

        // Public storefront (mybill-ecom) product listing.
        mvc.perform(get("/api/public/" + firm.code() + "/products"))
            .andExpect(content().string(not(containsString(WS_NAME))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + firm.schema() + "\".products WHERE product_name = ?",
            Long.class, WS_NAME)).isZero();
    }

    @Test
    void wholesalePurchasesNeverAppearInRetailPurchaseList() throws Exception {
        mvc.perform(get("/api/purchases").header("Authorization", firm.bearer()))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("ISO-1"))));
    }

    @Test
    void retailBillingOnSameNumericIdDoesNotTouchWholesaleStock() throws Exception {
        mvc.perform(post("/api/bills").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"paymentMethod":"CASH","billItems":[{"product":{"productId":%d},"quantity":3,"unitPrice":150,"discountPct":0,"gstPct":5}]}
                    """.formatted(wholesaleProductId)))
            .andExpect(status().isOk());

        assertThat(retailStock(firm, wholesaleProductId)).isEqualByComparingTo("47");
        assertThat(wholesaleStock(firm, wholesaleProductId)).isEqualByComparingTo("40");
    }

    @Test
    void deletingWholesalePurchaseDoesNotTouchRetail() throws Exception {
        String body = mvc.perform(post("/api/wholesale/purchases").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"supplierId":%d,"invoiceNumber":"ISO-DEL","invoiceDate":"%s",
                     "items":[{"wholesaleProductId":%d,"quantity":5,"purchaseRate":900,"gstPct":5}]}
                    """.formatted(supplierId, LocalDate.now(), wholesaleProductId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int purchaseId = json.readTree(body).get("wholesalePurchaseId").asInt();

        String before = retailSnapshot(firm);
        mvc.perform(delete("/api/wholesale/purchases/" + purchaseId).header("Authorization", firm.bearer()))
            .andExpect(status().isNoContent());
        assertThat(retailSnapshot(firm)).isEqualTo(before);
    }

    @Test
    void retailTablesHaveNoWholesaleColumns() {
        List<String> columns = jdbc.queryForList(
            "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name IN ('products','purchases','purchase_items','bills','bill_items','suppliers') "
                + "AND (column_name ILIKE '%wholesale%' OR column_name ILIKE '%purchase_type%')",
            String.class, firm.schema());
        assertThat(columns).isEmpty();
    }
}
