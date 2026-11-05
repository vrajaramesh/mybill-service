package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Made-to-order boutique order placed through the AI agent. Amounts are INR, snapshotted at creation. */
@Entity
@Table(name = "boutique_orders")
public class BoutiqueOrder {

    public static final String TYPE_GARMENT = "GARMENT";
    public static final String TYPE_FABRIC = "FABRIC";
    public static final String TYPE_READYMADE = "READYMADE";
    public static final String TYPE_MIXED = "MIXED";        // fabric and readymade lines in one order
    public static final String TYPE_DROPSHIP = "DROPSHIP";  // a supplier's products, shipped by the supplier (V16)

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Integer orderId;

    /** Human-readable number, e.g. SB-1045 (from boutique_order_no_seq). */
    @Column(name = "order_no", nullable = false, length = 20)
    private String orderNo;

    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BoutiqueOrderStatus status = BoutiqueOrderStatus.DRAFT;

    @Column(name = "delivery_name", nullable = false, length = 100) private String deliveryName;
    @Column(name = "delivery_phone", nullable = false, length = 20) private String deliveryPhone;
    @Column(name = "delivery_address_line1", nullable = false, length = 200) private String deliveryAddressLine1;
    @Column(name = "delivery_address_line2", length = 200) private String deliveryAddressLine2;
    @Column(name = "delivery_city", nullable = false, length = 100) private String deliveryCity;
    @Column(name = "delivery_state", nullable = false, length = 100) private String deliveryState;
    @Column(name = "delivery_pincode", nullable = false, length = 6) private String deliveryPincode;

    @Column(name = "subtotal", nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "delivery_charge", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryCharge;

    @Column(name = "total", nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    /** WHATSAPP, INSTAGRAM, WEB or SHOP. */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    /** Client-supplied key; retrying a create with the same key returns this order instead of a duplicate. */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /** today + lead time + transit days at order time (ShippingService). */
    @Column(name = "estimated_delivery_date")
    private java.time.LocalDate estimatedDeliveryDate;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Marketing attribution (V6): AD / IG_MEDIA + Meta's id, and the Click-to-WhatsApp click id. NULL = organic. */
    @Column(name = "source_type", length = 20) private String sourceType;
    @Column(name = "source_id", length = 40) private String sourceId;
    @Column(name = "ctwa_clid", length = 500) private String ctwaClid;

    /** PENDING_CALL (paid, the tailor must call for exact measurements) -> TAKEN; NULL = not needed. */
    /** GARMENT (stitched design) or FABRIC (metres of a fabric product, no stitching). */
    @Column(name = "order_type", length = 10, nullable = false) private String orderType = TYPE_GARMENT;
    @Column(name = "measurement_status", length = 20) private String measurementStatus;
    @Column(name = "stock_reduced_at") private LocalDateTime stockReducedAt;
    @Column(name = "measurement_taken_at") private LocalDateTime measurementTakenAt;
    @Column(name = "measurement_taken_by", length = 100) private String measurementTakenBy;

    /** Courier (V15): parcel weight the delivery charge was quoted on, and the shipment booked for the order. */
    @Column(name = "ship_weight_grams") private Integer shipWeightGrams;
    @Column(name = "courier", length = 40) private String courier;
    @Column(name = "awb", length = 40) private String awb;
    @Column(name = "courier_status", length = 60) private String courierStatus;
    @Column(name = "courier_location", length = 150) private String courierLocation;
    @Column(name = "courier_status_at") private LocalDateTime courierStatusAt;
    @Column(name = "courier_pickup_id", length = 40) private String courierPickupId;
    @Column(name = "courier_cost", precision = 10, scale = 2) private BigDecimal courierCost;
    /** Last courier status the customer was told about (so each update is messaged once). */
    @Column(name = "courier_notified", length = 60) private String courierNotified;
    @Column(name = "shipped_at") private LocalDateTime shippedAt;
    @Column(name = "delivered_at") private LocalDateTime deliveredAt;

    public Integer getShipWeightGrams() { return shipWeightGrams; }
    public void setShipWeightGrams(Integer v) { this.shipWeightGrams = v; }
    public String getCourier() { return courier; }
    public void setCourier(String v) { this.courier = v; }
    public String getAwb() { return awb; }
    public void setAwb(String v) { this.awb = v; }
    public String getCourierStatus() { return courierStatus; }
    public void setCourierStatus(String v) { this.courierStatus = v; }
    public String getCourierLocation() { return courierLocation; }
    public void setCourierLocation(String v) { this.courierLocation = v; }
    public LocalDateTime getCourierStatusAt() { return courierStatusAt; }
    public void setCourierStatusAt(LocalDateTime v) { this.courierStatusAt = v; }
    public String getCourierPickupId() { return courierPickupId; }
    public void setCourierPickupId(String v) { this.courierPickupId = v; }
    public BigDecimal getCourierCost() { return courierCost; }
    public void setCourierCost(BigDecimal v) { this.courierCost = v; }
    public String getCourierNotified() { return courierNotified; }
    public void setCourierNotified(String v) { this.courierNotified = v; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public void setShippedAt(LocalDateTime v) { this.shippedAt = v; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime v) { this.deliveredAt = v; }

    /** Tracking link of a non-Delhivery courier (V16); Delhivery's is built from the AWB. */
    @Column(name = "courier_tracking_url", length = 500) private String courierTrackingUrl;
    public String getCourierTrackingUrl() { return courierTrackingUrl; }
    public void setCourierTrackingUrl(String v) { this.courierTrackingUrl = v; }

    /** The link the customer tracks her parcel with (null when not shipped, or another courier without a link). */
    public String trackingUrl() {
        if (courierTrackingUrl != null && !courierTrackingUrl.isBlank()) return courierTrackingUrl;
        return awb != null && isDelhivery() ? com.example.mybill.boutique.courier.DelhiveryClient.trackingUrl(awb) : null;
    }

    /** Shipped with Delhivery (booked by us, or the supplier used Delhivery): status is synced automatically. */
    public boolean isDelhivery() {
        return courier == null || courier.equalsIgnoreCase("DELHIVERY");
    }

    /** Dropship (V16): the supplier, what we owe them (cost + their courier charge) and how far it got. */
    @Column(name = "supplier_id") private Integer supplierId;
    @Column(name = "supplier_name", length = 150) private String supplierName;
    @Column(name = "supplier_cost", precision = 10, scale = 2) private BigDecimal supplierCost;
    @Column(name = "supplier_courier", precision = 10, scale = 2) private BigDecimal supplierCourier;
    @Column(name = "supplier_zone", length = 10) private String supplierZone;
    @Column(name = "supplier_sent_at") private LocalDateTime supplierSentAt;
    @Column(name = "supplier_paid_at") private LocalDateTime supplierPaidAt;
    @Column(name = "supplier_paid_ref", length = 100) private String supplierPaidRef;
    @Column(name = "supplier_paid_by", length = 100) private String supplierPaidBy;

    public boolean isDropship() { return TYPE_DROPSHIP.equals(orderType); }
    public Integer getSupplierId() { return supplierId; }
    public void setSupplierId(Integer v) { this.supplierId = v; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String v) { this.supplierName = v; }
    public BigDecimal getSupplierCost() { return supplierCost; }
    public void setSupplierCost(BigDecimal v) { this.supplierCost = v; }
    public BigDecimal getSupplierCourier() { return supplierCourier; }
    public void setSupplierCourier(BigDecimal v) { this.supplierCourier = v; }
    public String getSupplierZone() { return supplierZone; }
    public void setSupplierZone(String v) { this.supplierZone = v; }
    public LocalDateTime getSupplierSentAt() { return supplierSentAt; }
    public void setSupplierSentAt(LocalDateTime v) { this.supplierSentAt = v; }
    public LocalDateTime getSupplierPaidAt() { return supplierPaidAt; }
    public void setSupplierPaidAt(LocalDateTime v) { this.supplierPaidAt = v; }
    public String getSupplierPaidRef() { return supplierPaidRef; }
    public void setSupplierPaidRef(String v) { this.supplierPaidRef = v; }
    public String getSupplierPaidBy() { return supplierPaidBy; }
    public void setSupplierPaidBy(String v) { this.supplierPaidBy = v; }

    @Column(name = "stitching_order_id")
    private Integer stitchingOrderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("itemId")
    private List<BoutiqueOrderItem> items = new ArrayList<>();

    public void addItem(BoutiqueOrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public Integer getOrderId() { return orderId; }
    public void setOrderId(Integer orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }
    public BoutiqueOrderStatus getStatus() { return status; }
    public void setStatus(BoutiqueOrderStatus status) { this.status = status; }
    public String getDeliveryName() { return deliveryName; }
    public void setDeliveryName(String v) { this.deliveryName = v; }
    public String getDeliveryPhone() { return deliveryPhone; }
    public void setDeliveryPhone(String v) { this.deliveryPhone = v; }
    public String getDeliveryAddressLine1() { return deliveryAddressLine1; }
    public void setDeliveryAddressLine1(String v) { this.deliveryAddressLine1 = v; }
    public String getDeliveryAddressLine2() { return deliveryAddressLine2; }
    public void setDeliveryAddressLine2(String v) { this.deliveryAddressLine2 = v; }
    public String getDeliveryCity() { return deliveryCity; }
    public void setDeliveryCity(String v) { this.deliveryCity = v; }
    public String getDeliveryState() { return deliveryState; }
    public void setDeliveryState(String v) { this.deliveryState = v; }
    public String getDeliveryPincode() { return deliveryPincode; }
    public void setDeliveryPincode(String v) { this.deliveryPincode = v; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getDeliveryCharge() { return deliveryCharge; }
    public void setDeliveryCharge(BigDecimal deliveryCharge) { this.deliveryCharge = deliveryCharge; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public java.time.LocalDate getEstimatedDeliveryDate() { return estimatedDeliveryDate; }
    public void setEstimatedDeliveryDate(java.time.LocalDate v) { this.estimatedDeliveryDate = v; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime v) { this.paidAt = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String v) { this.sourceId = v; }
    public String getCtwaClid() { return ctwaClid; }
    public void setCtwaClid(String v) { this.ctwaClid = v; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String v) { this.orderType = v; }
    public LocalDateTime getStockReducedAt() { return stockReducedAt; }
    public void setStockReducedAt(LocalDateTime v) { this.stockReducedAt = v; }
    /** Ready goods (fabric / readymade): no stitching, packed and dispatched from stock. */
    public boolean isGoods() { return !TYPE_GARMENT.equals(orderType); }
    public String getMeasurementStatus() { return measurementStatus; }
    public void setMeasurementStatus(String v) { this.measurementStatus = v; }
    public LocalDateTime getMeasurementTakenAt() { return measurementTakenAt; }
    public void setMeasurementTakenAt(LocalDateTime v) { this.measurementTakenAt = v; }
    public String getMeasurementTakenBy() { return measurementTakenBy; }
    public void setMeasurementTakenBy(String v) { this.measurementTakenBy = v; }
    public Integer getStitchingOrderId() { return stitchingOrderId; }
    public void setStitchingOrderId(Integer stitchingOrderId) { this.stitchingOrderId = stitchingOrderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<BoutiqueOrderItem> getItems() { return items; }
}
