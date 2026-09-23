package com.example.mybill.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.example.mybill.service.PurchaseItem;
import com.example.mybill.service.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "debit_note_items")
public class DebitNoteItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "debit_note_item_id")
    private Integer debitNoteItemId;
    @ManyToOne(optional = false) @JoinColumn(name = "debit_note_id") @JsonIgnore
    private DebitNote debitNote;
    @ManyToOne(optional = false) @JoinColumn(name = "purchase_item_id")
    private PurchaseItem purchaseItem;
    @ManyToOne(optional = false) @JoinColumn(name = "product_id")
    private Product product;
    @Column(name = "quantity", nullable = false, precision = 10, scale = 2) private BigDecimal quantity;
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2) private BigDecimal unitPrice;
    @Column(name = "gst", precision = 10, scale = 2) private BigDecimal gst;
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2) private BigDecimal totalAmount;
    @Column(name = "final_amount", nullable = false, precision = 12, scale = 2) private BigDecimal finalAmount;

    public Integer getDebitNoteItemId() { return debitNoteItemId; }
    public void setDebitNoteItemId(Integer value) { debitNoteItemId = value; }
    public DebitNote getDebitNote() { return debitNote; }
    public void setDebitNote(DebitNote value) { debitNote = value; }
    public PurchaseItem getPurchaseItem() { return purchaseItem; }
    public void setPurchaseItem(PurchaseItem value) { purchaseItem = value; }
    public Product getProduct() { return product; }
    public void setProduct(Product value) { product = value; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal value) { quantity = value; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { unitPrice = value; }
    public BigDecimal getGst() { return gst; }
    public void setGst(BigDecimal value) { gst = value; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal value) { totalAmount = value; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal value) { finalAmount = value; }
}