package com.example.mybill.boutique.courier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delhivery B2C API (Authorization: Token &lt;api token&gt;). Production https://track.delhivery.com, staging
 * https://staging-express.delhivery.com. Used for: pincode serviceability, shipping cost, shipment creation (AWB),
 * pickup request, shipping label, tracking and cancellation.
 *
 * Off (enabled() = false) until DELHIVERY_API_TOKEN, DELHIVERY_PICKUP_LOCATION (the warehouse name exactly as
 * registered in Delhivery One) and DELHIVERY_ORIGIN_PINCODE are set; callers then keep the flat delivery charge.
 * Never logs the token.
 */
@Component
public class DelhiveryClient {

    private static final Logger log = LoggerFactory.getLogger(DelhiveryClient.class);
    private static final Duration RATE_TTL = Duration.ofHours(6);
    private static final Duration PIN_TTL = Duration.ofHours(24);

    @Value("${delhivery.base-url:https://track.delhivery.com}") private String baseUrl;
    @Value("${delhivery.token:}") private String token;
    @Value("${delhivery.pickup-location:}") private String pickupLocation;
    @Value("${delhivery.origin-pincode:}") private String originPincode;
    /** Surface (cheaper, slower) or Express. */
    @Value("${delhivery.shipping-mode:Surface}") private String shippingMode;
    @Value("${delhivery.seller-name:SRISA Fabrics}") private String sellerName;

    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private record Cached<T>(T value, long expiresAt) {}
    private final Map<String, Cached<BigDecimal>> rates = new ConcurrentHashMap<>();
    private final Map<String, Cached<Boolean>> pins = new ConcurrentHashMap<>();

    public boolean enabled() {
        return !token.isBlank() && !pickupLocation.isBlank() && originPincode.matches("\\d{6}");
    }

    public String shippingMode() { return shippingMode; }

    public static String trackingUrl(String awb) {
        return awb == null ? null : "https://www.delhivery.com/track-v2/package/" + awb;
    }

    /** Prepaid delivery to this pincode; empty = could not check (treat as unknown, not as "no"). */
    public Optional<Boolean> serviceable(String pincode) {
        if (!enabled() || pincode == null || !pincode.matches("\\d{6}")) return Optional.empty();
        Cached<Boolean> hit = pins.get(pincode);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) return Optional.of(hit.value());
        try {
            JsonNode body = getJson("/c/api/pin-codes/json/?filter_codes=" + pincode);
            JsonNode codes = body.path("delivery_codes");
            boolean ok = false;
            for (JsonNode c : codes) {
                JsonNode pin = c.path("postal_code");
                if (pincode.equals(pin.path("pin").asText()) && !"N".equalsIgnoreCase(pin.path("pre_paid").asText("Y"))) ok = true;
            }
            pins.put(pincode, new Cached<>(ok, System.currentTimeMillis() + PIN_TTL.toMillis()));
            return Optional.of(ok);
        } catch (Exception e) {
            log.warn("Delhivery pincode check failed for {}: {}", pincode, e.toString());
            return Optional.empty();
        }
    }

    /** Delhivery's prepaid delivery cost (incl. GST) for a parcel of chargeableGrams to the pincode; empty on error. */
    public Optional<BigDecimal> shippingCost(String pincode, int chargeableGrams) {
        if (!enabled() || pincode == null || !pincode.matches("\\d{6}") || chargeableGrams <= 0) return Optional.empty();
        String mode = "Express".equalsIgnoreCase(shippingMode) ? "E" : "S";
        String key = mode + ":" + pincode + ":" + chargeableGrams;
        Cached<BigDecimal> hit = rates.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) return Optional.of(hit.value());
        try {
            String raw = get("/api/kinko/v1/invoice/charges/.json?md=" + mode + "&ss=Delivered&pt=Pre-paid"
                + "&o_pin=" + originPincode + "&d_pin=" + pincode + "&cgm=" + chargeableGrams);
            BigDecimal total = totalAmount(raw);
            if (total == null || total.signum() <= 0) {
                log.warn("Delhivery returned no shipping cost for {} ({} g): {}", pincode, chargeableGrams, abbreviate(raw));
                return Optional.empty();
            }
            rates.put(key, new Cached<>(total, System.currentTimeMillis() + RATE_TTL.toMillis()));
            return Optional.of(total);
        } catch (Exception e) {
            log.warn("Delhivery shipping cost failed for {} ({} g): {}", pincode, chargeableGrams, e.toString());
            return Optional.empty();
        }
    }

    /** The charges API answers JSON ([{"total_amount": ..}]) or XML (&lt;total_amount&gt;..). */
    BigDecimal totalAmount(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("[") || text.startsWith("{")) {
            JsonNode node = objectMapper.readTree(text);
            JsonNode first = node.isArray() ? node.path(0) : node;
            return first.hasNonNull("total_amount") ? new BigDecimal(first.get("total_amount").asText()) : null;
        }
        Matcher m = Pattern.compile("<total_amount>\\s*([0-9.]+)\\s*</total_amount>").matcher(text);
        return m.find() ? new BigDecimal(m.group(1)) : null;
    }

    public record Parcel(int weightGrams, int lengthCm, int breadthCm, int heightCm) {}

    public record Shipment(String orderNo, String name, String address, String pincode, String city, String state,
                           String phone, String productsDesc, BigDecimal totalAmount, int quantity, Parcel parcel,
                           LocalDate orderDate) {}

    /** Creates a prepaid shipment and returns its AWB (waybill). Throws CourierException with Delhivery's remark. */
    public String createShipment(Shipment s) {
        requireEnabled();
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("name", s.name());
        shipment.put("add", s.address());
        shipment.put("pin", s.pincode());
        shipment.put("city", s.city());
        shipment.put("state", s.state());
        shipment.put("country", "India");
        shipment.put("phone", s.phone());
        shipment.put("order", s.orderNo());
        shipment.put("payment_mode", "Prepaid");
        shipment.put("products_desc", s.productsDesc());
        shipment.put("total_amount", s.totalAmount().toPlainString());
        shipment.put("quantity", String.valueOf(s.quantity()));
        shipment.put("order_date", s.orderDate().toString());
        shipment.put("seller_name", sellerName);
        shipment.put("weight", String.valueOf(s.parcel().weightGrams()));
        shipment.put("shipment_length", String.valueOf(s.parcel().lengthCm()));
        shipment.put("shipment_width", String.valueOf(s.parcel().breadthCm()));
        shipment.put("shipment_height", String.valueOf(s.parcel().heightCm()));
        shipment.put("shipping_mode", "Express".equalsIgnoreCase(shippingMode) ? "Express" : "Surface");
        Map<String, Object> data = Map.of("shipments", List.of(shipment), "pickup_location", Map.of("name", pickupLocation));
        try {
            String form = "format=json&data=" + URLEncoder.encode(objectMapper.writeValueAsString(data), StandardCharsets.UTF_8);
            HttpRequest request = base("/api/cmu/create.json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            JsonNode body = send(request);
            for (JsonNode pkg : body.path("packages")) {
                String waybill = pkg.path("waybill").asText("");
                if ("Success".equalsIgnoreCase(pkg.path("status").asText()) && !waybill.isBlank()) return waybill;
                throw new CourierException("Delhivery did not accept the shipment: " + remarks(pkg));
            }
            throw new CourierException("Delhivery did not accept the shipment: " + abbreviate(body.toString()));
        } catch (CourierException e) {
            throw e;
        } catch (Exception e) {
            throw new CourierException("Could not reach Delhivery to book the shipment: " + e.getMessage());
        }
    }

    /** Asks Delhivery to collect from the pickup location; returns the pickup id, or empty (e.g. one already booked). */
    public Optional<String> requestPickup(LocalDate date, String time, int packages) {
        requireEnabled();
        try {
            Map<String, Object> payload = Map.of("pickup_location", pickupLocation, "pickup_date", date.toString(),
                "pickup_time", time, "expected_package_count", packages);
            HttpRequest request = base("/fm/request/new/").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
            JsonNode body = send(request);
            String id = body.path("pickup_id").asText("");
            if (!id.isBlank()) return Optional.of(id);
            log.info("Delhivery pickup not created for {}: {}", date, abbreviate(body.toString()));
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Delhivery pickup request failed for {}: {}", date, e.getMessage());
            return Optional.empty();
        }
    }

    /** Link to the shipping label PDF to print and stick on the parcel. */
    public Optional<String> labelUrl(String awb) {
        requireEnabled();
        try {
            JsonNode body = getJson("/api/p/packing_slip?wbns=" + enc(awb) + "&pdf=true");
            for (JsonNode pkg : body.path("packages")) {
                String url = pkg.path("pdf_download_link").asText("");
                if (!url.isBlank()) return Optional.of(url);
            }
            log.warn("Delhivery label has no PDF link for {}: {}", awb, abbreviate(body.toString()));
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Delhivery label failed for {}: {}", awb, e.toString());
            return Optional.empty();
        }
    }

    public record Tracking(String status, String statusType, String location, String instructions,
                           String statusDateTime, String expectedDelivery) {
        public boolean delivered() { return "Delivered".equalsIgnoreCase(status) || "DL".equalsIgnoreCase(statusType); }
        public boolean outForDelivery() {
            String s = (status == null ? "" : status) + " " + (instructions == null ? "" : instructions);
            return s.toLowerCase(Locale.ROOT).contains("out for delivery");
        }
        public boolean returning() { return "RT".equalsIgnoreCase(statusType) || "RTO".equalsIgnoreCase(statusType); }
    }

    public Optional<Tracking> track(String awb) {
        if (!enabled() || awb == null || awb.isBlank()) return Optional.empty();
        try {
            JsonNode body = getJson("/api/v1/packages/json/?waybill=" + enc(awb));
            JsonNode shipment = body.path("ShipmentData").path(0).path("Shipment");
            if (shipment.isMissingNode()) return Optional.empty();
            JsonNode st = shipment.path("Status");
            return Optional.of(new Tracking(text(st, "Status"), text(st, "StatusType"), text(st, "StatusLocation"),
                text(st, "Instructions"), text(st, "StatusDateTime"),
                Optional.ofNullable(text(shipment, "ExpectedDeliveryDate")).orElse(text(shipment, "PromisedDeliveryDate"))));
        } catch (Exception e) {
            log.warn("Delhivery tracking failed for {}: {}", awb, e.toString());
            return Optional.empty();
        }
    }

    /** Cancels a shipment that has not been picked up yet. */
    public void cancel(String awb) {
        requireEnabled();
        try {
            HttpRequest request = base("/api/p/edit").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                    Map.of("waybill", awb, "cancellation", "true")))).build();
            JsonNode body = send(request);
            if (body.has("status") && !body.path("status").asBoolean(true)) {
                throw new CourierException("Delhivery could not cancel " + awb + ": " + abbreviate(body.toString()));
            }
        } catch (CourierException e) {
            throw e;
        } catch (Exception e) {
            throw new CourierException("Could not reach Delhivery to cancel " + awb + ": " + e.getMessage());
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private void requireEnabled() {
        if (!enabled()) throw new CourierException("Delhivery is not set up yet (API token, pickup location and origin pincode)");
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Token " + token)
            .header("Accept", "application/json");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> r = http.send(base(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) throw new CourierException("HTTP " + r.statusCode() + " " + abbreviate(r.body()));
        return r.body();
    }

    private JsonNode getJson(String path) throws Exception {
        return objectMapper.readTree(get(path));
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) throw new CourierException("HTTP " + r.statusCode() + " " + abbreviate(r.body()));
        return objectMapper.readTree(r.body());
    }

    private static String remarks(JsonNode pkg) {
        List<String> out = new ArrayList<>();
        pkg.path("remarks").forEach(r -> { if (!r.asText("").isBlank()) out.add(r.asText()); });
        return out.isEmpty() ? pkg.toString() : String.join("; ", out);
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText("");
        return v.isBlank() || "null".equals(v) ? null : v;
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
