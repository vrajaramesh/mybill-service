package com.example.mybill.wholesale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WholesalePurchaseIntegrationTest extends AbstractFirmIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private FirmCtx firm;
    private int supplierId;
    private int otherSupplierId;

    @BeforeAll
    void setUp() {
        firm = registerFirm("wsbuy");
        supplierId = insertSupplier(firm, "Surat Textiles", "24AAAAA0000A1Z5");
        otherSupplierId = insertSupplier(firm, "Erode Mills", "33BBBBB1111B1Z5");
    }

    private ResultActions postPurchase(String body) throws Exception {
        return mvc.perform(post("/api/wholesale/purchases").header("Authorization", firm.bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String newProductPurchase(String invoice, String code, String name, String qty, String rate, boolean interstate) {
        return """
            {"supplierId":%d,"invoiceNumber":"%s","invoiceDate":"%s","interstate":%s,
             "items":[{"newProduct":{"productCode":%s,"productName":"%s","productType":"Fabric","hsnCode":"5208",
                                     "description":"60 inch width","unit":"Meters"},
                       "quantity":%s,"purchaseRate":%s,"gstPct":5}]}
            """.formatted(supplierId, invoice, LocalDate.now(), interstate,
                code == null ? "null" : "\"" + code + "\"", name, qty, rate);
    }

    private int createWithNewProduct(String invoice, String code, String name, String qty, String rate) throws Exception {
        String body = postPurchase(newProductPurchase(invoice, code, name, qty, rate, false))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("items").get(0).get("wholesaleProductId").asInt();
    }

    @Test
    void purchaseWithNewProduct_createsWholesaleProductWithStockAndSplitsCgstSgst() throws Exception {
        String body = postPurchase(newProductPurchase("WS-001", "COT-60", "Cotton Bale 60in", "100", "50", false))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.supplierName").value("Surat Textiles"))
            .andExpect(jsonPath("$.interstate").value(false))
            .andExpect(jsonPath("$.taxableAmount").value(5000.0))
            .andExpect(jsonPath("$.cgstAmount").value(125.0))
            .andExpect(jsonPath("$.sgstAmount").value(125.0))
            .andExpect(jsonPath("$.igstAmount").value(0.0))
            .andExpect(jsonPath("$.totalAmount").value(5250.0))
            .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
            .andExpect(jsonPath("$.items[0].hsnCode").value("5208"))
            .andReturn().getResponse().getContentAsString();
        int productId = json.readTree(body).get("items").get(0).get("wholesaleProductId").asInt();

        mvc.perform(get("/api/wholesale/products/" + productId).header("Authorization", firm.bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.productCode").value("COT-60"))
            .andExpect(jsonPath("$.productName").value("Cotton Bale 60in"))
            .andExpect(jsonPath("$.productType").value("Fabric"))
            .andExpect(jsonPath("$.hsnCode").value("5208"))
            .andExpect(jsonPath("$.description").value("60 inch width"))
            .andExpect(jsonPath("$.unit").value("Meters"))
            .andExpect(jsonPath("$.availableQuantity").value(100.0))
            .andExpect(jsonPath("$.purchaseRate").value(50.0))
            .andExpect(jsonPath("$.gstPct").value(5.0))
            .andExpect(jsonPath("$.supplierName").value("Surat Textiles"))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.sellingPrice").doesNotExist());
    }

    @Test
    void purchaseOfExistingProduct_accumulatesStock_updatesLatestRateAndSupplier_andChargesIgstInterstate() throws Exception {
        int productId = createWithNewProduct("WS-010", "SILK-1", "Raw Silk", "10", "400");

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"ER-77","invoiceDate":"%s","interstate":true,"paidAmount":100,
             "items":[{"wholesaleProductId":%d,"quantity":2.5,"purchaseRate":420,"gstPct":12}]}
            """.formatted(otherSupplierId, LocalDate.now(), productId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.taxableAmount").value(1050.0))
            .andExpect(jsonPath("$.igstAmount").value(126.0))
            .andExpect(jsonPath("$.cgstAmount").value(0.0))
            .andExpect(jsonPath("$.totalAmount").value(1176.0))
            .andExpect(jsonPath("$.paymentStatus").value("PARTIAL"));

        mvc.perform(get("/api/wholesale/products/" + productId).header("Authorization", firm.bearer()))
            .andExpect(jsonPath("$.availableQuantity").value(12.5))
            .andExpect(jsonPath("$.purchaseRate").value(420.0))
            .andExpect(jsonPath("$.supplierName").value("Erode Mills"));
    }

    @Test
    void deletePurchase_reversesWholesaleStock() throws Exception {
        int productId = createWithNewProduct("WS-020", null, "Polyester Roll", "30", "20");
        String body = postPurchase("""
            {"supplierId":%d,"invoiceNumber":"WS-021","invoiceDate":"%s",
             "items":[{"wholesaleProductId":%d,"quantity":5,"purchaseRate":20,"gstPct":5}]}
            """.formatted(supplierId, LocalDate.now(), productId))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int purchaseId = json.readTree(body).get("wholesalePurchaseId").asInt();
        assertThat(wholesaleStock(firm, productId)).isEqualByComparingTo("35");

        mvc.perform(delete("/api/wholesale/purchases/" + purchaseId).header("Authorization", firm.bearer()))
            .andExpect(status().isNoContent());
        assertThat(wholesaleStock(firm, productId)).isEqualByComparingTo("30");
        // Purchases are cancelled (kept as the stock ledger's source document), not deleted.
        mvc.perform(get("/api/wholesale/purchases/" + purchaseId).header("Authorization", firm.bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void validationErrors_returnClearMessages() throws Exception {
        String today = LocalDate.now().toString();

        postPurchase("{\"supplierId\":%d,\"invoiceNumber\":\"V-1\",\"invoiceDate\":\"%s\",\"items\":[]}".formatted(supplierId, today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Add at least one item"));

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"V-2","invoiceDate":"%s",
             "items":[{"newProduct":{"productName":"X","unit":"Pieces"},"quantity":0,"purchaseRate":10,"gstPct":5}]}
            """.formatted(supplierId, today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors['items[0].quantity']").value("Quantity must be greater than 0"));

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"V-3","invoiceDate":"%s",
             "items":[{"newProduct":{"productName":"X","unit":"Pieces","hsnCode":"52A"},"quantity":1,"purchaseRate":10,"gstPct":5}]}
            """.formatted(supplierId, today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("HSN code must be 4, 6 or 8 digits"));

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"V-4","invoiceDate":"%s",
             "items":[{"quantity":1,"purchaseRate":10,"gstPct":5}]}
            """.formatted(supplierId, today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Item 1: select an existing wholesale product or enter a new one")));

        postPurchase("""
            {"supplierId":99999,"invoiceNumber":"V-5","invoiceDate":"%s",
             "items":[{"newProduct":{"productName":"X","unit":"Pieces"},"quantity":1,"purchaseRate":10,"gstPct":5}]}
            """.formatted(today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Supplier #99999 not found"));

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"V-6","invoiceDate":"%s","paidAmount":1000,
             "items":[{"newProduct":{"productName":"X","unit":"Pieces"},"quantity":1,"purchaseRate":10,"gstPct":5}]}
            """.formatted(supplierId, today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("cannot exceed the invoice total")));

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"V-7","invoiceDate":"%s",
             "items":[{"newProduct":{"productName":"X","unit":"Pieces"},"quantity":1,"purchaseRate":10,"gstPct":150}]}
            """.formatted(supplierId, today))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("GST % cannot exceed 100"));

        // Nothing from the rejected requests was persisted.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + firm.schema()
            + "\".wholesale_purchases WHERE invoice_number LIKE 'V-%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + firm.schema()
            + "\".wholesale_products WHERE product_name = 'X'", Long.class)).isZero();
    }

    @Test
    void duplicateSupplierInvoice_isRejected_butSameNumberFromAnotherSupplierIsAllowed() throws Exception {
        createWithNewProduct("DUP-1", null, "Dup Fabric", "1", "10");
        postPurchase(newProductPurchase("dup-1", null, "Dup Fabric 2", "1", "10", false))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already recorded")));

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"DUP-1","invoiceDate":"%s",
             "items":[{"newProduct":{"productName":"Dup Fabric 3","unit":"Pieces"},"quantity":1,"purchaseRate":10,"gstPct":5}]}
            """.formatted(otherSupplierId, LocalDate.now()))
            .andExpect(status().isCreated());
    }

    @Test
    void duplicateProductCode_isRejected_andRollsBackTheWholePurchase() throws Exception {
        createWithNewProduct("CODE-1", "UNIQ-1", "Code Owner", "1", "10");
        long purchasesBefore = count(firm, "wholesale_purchases");
        postPurchase(newProductPurchase("CODE-2", "uniq-1", "Code Thief", "1", "10", false))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already used")));
        assertThat(count(firm, "wholesale_purchases")).isEqualTo(purchasesBefore);
    }

    @Test
    void inactiveProduct_cannotBePurchased() throws Exception {
        int productId = createWithNewProduct("INA-1", null, "Old Stock", "1", "10");
        mvc.perform(put("/api/wholesale/products/" + productId).header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productName\":\"Old Stock\",\"unit\":\"Meters\",\"isActive\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isActive").value(false))
            .andExpect(jsonPath("$.availableQuantity").value(1.0));

        postPurchase("""
            {"supplierId":%d,"invoiceNumber":"INA-2","invoiceDate":"%s",
             "items":[{"wholesaleProductId":%d,"quantity":1,"purchaseRate":10,"gstPct":5}]}
            """.formatted(supplierId, LocalDate.now(), productId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("is inactive")));

        mvc.perform(get("/api/wholesale/products?activeOnly=true").header("Authorization", firm.bearer()))
            .andExpect(jsonPath("$[?(@.wholesaleProductId == " + productId + ")]").isEmpty());
    }

    @Test
    void productApi_createUpdateAndImages() throws Exception {
        String body = mvc.perform(post("/api/wholesale/products").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productName\":\"Chiffon\",\"unit\":\"Meters\",\"hsnCode\":\"540752\",\"gstPct\":5,\"availableQuantity\":999}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.availableQuantity").value(0.0))
            .andReturn().getResponse().getContentAsString();
        int productId = json.readTree(body).get("wholesaleProductId").asInt();

        String img = mvc.perform(post("/api/wholesale/products/" + productId + "/images").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageUrl\":\"https://res.cloudinary.com/demo/chiffon.jpg\",\"publicId\":\"chiffon\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int imageId = json.readTree(img).get("imageId").asInt();

        mvc.perform(get("/api/wholesale/products/" + productId).header("Authorization", firm.bearer()))
            .andExpect(jsonPath("$.images[0].imageUrl").value("https://res.cloudinary.com/demo/chiffon.jpg"));

        mvc.perform(post("/api/wholesale/products/" + productId + "/images").header("Authorization", firm.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"imageUrl\":\"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/wholesale/products/" + productId + "/images/" + imageId).header("Authorization", firm.bearer()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/wholesale/products/" + productId).header("Authorization", firm.bearer()))
            .andExpect(jsonPath("$.images").isEmpty());
    }

    @Test
    void listEndpoint_returnsPurchasesWithItems() throws Exception {
        createWithNewProduct("LIST-1", null, "List Fabric", "4", "25");
        String body = mvc.perform(get("/api/wholesale/purchases").header("Authorization", firm.bearer()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode match = null;
        for (JsonNode p : json.readTree(body)) if ("LIST-1".equals(p.get("invoiceNumber").asText())) match = p;
        assertThat(match).isNotNull();
        assertThat(match.get("items").get(0).get("productName").asText()).isEqualTo("List Fabric");
    }

    @Test
    void authAndTenantRules() throws Exception {
        mvc.perform(get("/api/wholesale/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/wholesale/products").header("Authorization", superadminBearer()))
            .andExpect(status().isForbidden());

        // A firm registered after startup gets its tables lazily and sees none of this firm's data.
        FirmCtx other = registerFirm("wsother");
        assertThat(tableExists(other, "wholesale_products")).isFalse();
        createWithNewProduct("TEN-1", null, "Tenant A Fabric", "1", "10");
        mvc.perform(get("/api/wholesale/products").header("Authorization", other.bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
        assertThat(tableExists(other, "wholesale_products")).isTrue();
        assertThat(jdbc.queryForObject("SELECT MAX(version) FROM \"" + other.schema() + "\".wholesale_schema_version",
            Integer.class)).isEqualTo(1);
    }
}
