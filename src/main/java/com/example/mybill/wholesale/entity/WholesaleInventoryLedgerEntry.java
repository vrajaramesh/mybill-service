package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One wholesale stock movement. Append-only (no setters after creation are used by the application; corrections are
 * new REVERSAL / ADJUSTMENT rows). referenceType + referenceId identify the source document.
 */
@Entity
@Table(name = "wholesale_inventory_ledger")
public class WholesaleInventoryLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_id")
    private Long ledgerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wholesale_product_id", nullable = false, updatable = false)
    private WholesaleProduct product;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20, updatable = false)
    private WholesaleInventoryTransactionType transactionType;

    @Column(name = "reference_type", nullable = false, length = 20, updatable = false) private String referenceType;
    @Column(name = "reference_id", nullable = false, updatable = false) private Integer referenceId;
    @Column(name = "reference_number", length = 60, updatable = false) private String referenceNumber;
    @Column(name = "reverses_ledger_id", updatable = false) private Long reversesLedgerId;
    @Column(name = "quantity_in", nullable = false, precision = 12, scale = 3, updatable = false) private BigDecimal quantityIn;
    @Column(name = "quantity_out", nullable = false, precision = 12, scale = 3, updatable = false) private BigDecimal quantityOut;
    @Column(name = "balance_quantity", nullable = false, precision = 12, scale = 3, updatable = false) private BigDecimal balanceQuantity;
    @Column(name = "unit", nullable = false, length = 20, updatable = false) private String unit;
    @Column(name = "rate", precision = 12, scale = 2, updatable = false) private BigDecimal rate;
    @Column(name = "transaction_date", nullable = false, updatable = false) private LocalDate transactionDate;
    @Column(name = "notes", length = 255, updatable = false) private String notes;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "created_by", length = 100, updatable = false) private String createdBy;

    protected WholesaleInventoryLedgerEntry() {}

    public WholesaleInventoryLedgerEntry(WholesaleProduct product, WholesaleInventoryTransactionType type, String referenceType,
                                         Integer referenceId, String referenceNumber, Long reversesLedgerId,
                                         BigDecimal quantityIn, BigDecimal quantityOut, BigDecimal balanceQuantity,
                                         BigDecimal rate, LocalDate transactionDate, String notes, String createdBy) {
        this.product = product;
        this.transactionType = type;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.referenceNumber = referenceNumber;
        this.reversesLedgerId = reversesLedgerId;
        this.quantityIn = quantityIn;
        this.quantityOut = quantityOut;
        this.balanceQuantity = balanceQuantity;
        this.unit = product.getUnit();
        this.rate = rate;
        this.transactionDate = transactionDate;
        this.notes = notes == null ? null : notes.length() > 255 ? notes.substring(0, 255) : notes;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getLedgerId() { return ledgerId; }
    public WholesaleProduct getProduct() { return product; }
    public WholesaleInventoryTransactionType getTransactionType() { return transactionType; }
    public String getReferenceType() { return referenceType; }
    public Integer getReferenceId() { return referenceId; }
    public String getReferenceNumber() { return referenceNumber; }
    public Long getReversesLedgerId() { return reversesLedgerId; }
    public BigDecimal getQuantityIn() { return quantityIn; }
    public BigDecimal getQuantityOut() { return quantityOut; }
    public BigDecimal getBalanceQuantity() { return balanceQuantity; }
    public String getUnit() { return unit; }
    public BigDecimal getRate() { return rate; }
    public LocalDate getTransactionDate() { return transactionDate; }
    public String getNotes() { return notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
