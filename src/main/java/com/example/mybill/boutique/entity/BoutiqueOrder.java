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
    public Integer getStitchingOrderId() { return stitchingOrderId; }
    public void setStitchingOrderId(Integer stitchingOrderId) { this.stitchingOrderId = stitchingOrderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<BoutiqueOrderItem> getItems() { return items; }
}
