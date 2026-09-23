package com.example.mybill.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.example.mybill.service.Purchase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "debit_notes")
public class DebitNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "debit_note_id")
    private Integer debitNoteId;
    @Column(name = "note_number", nullable = false, unique = true, length = 40)
    private String noteNumber;
    @ManyToOne(optional = false) @JoinColumn(name = "purchase_id") @JsonIgnore
    private Purchase purchase;
    @Column(name = "note_date", nullable = false) private LocalDate noteDate;
    @Column(name = "status", nullable = false, length = 20) private String status = "ISSUED";
    private String reason;
    @Column(name = "total_amount", precision = 12, scale = 2) private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(name = "gst", precision = 12, scale = 2) private BigDecimal gst = BigDecimal.ZERO;
    @Column(name = "final_amount", precision = 12, scale = 2) private BigDecimal finalAmount = BigDecimal.ZERO;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @OneToMany(mappedBy = "debitNote", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DebitNoteItem> items;

    public Integer getDebitNoteId() { return debitNoteId; }
    public void setDebitNoteId(Integer value) { debitNoteId = value; }
    public String getNoteNumber() { return noteNumber; }
    public void setNoteNumber(String value) { noteNumber = value; }
    public Purchase getPurchase() { return purchase; }
    public void setPurchase(Purchase value) { purchase = value; }
    public LocalDate getNoteDate() { return noteDate; }
    public void setNoteDate(LocalDate value) { noteDate = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal value) { totalAmount = value; }
    public BigDecimal getGst() { return gst; }
    public void setGst(BigDecimal value) { gst = value; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal value) { finalAmount = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public List<DebitNoteItem> getItems() { return items; }
    public void setItems(List<DebitNoteItem> value) { items = value; }
}