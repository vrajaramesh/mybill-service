package com.example.mybill.wholesale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Audit trail entry of a wholesale sales document (created, updated, issued, payment, void, cancelled, ...). */
@Entity
@Table(name = "wholesale_document_events")
public class WholesaleDocumentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Integer eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_document_id", nullable = false)
    private WholesaleSalesDocument document;

    @Column(name = "event_type", nullable = false, length = 30) private String eventType;
    @Column(name = "event_at", nullable = false) private LocalDateTime eventAt;
    @Column(name = "event_by", length = 100) private String eventBy;
    @Column(name = "details", length = 1000) private String details;

    public static WholesaleDocumentEvent of(WholesaleSalesDocument d, String type, String by, String details) {
        WholesaleDocumentEvent e = new WholesaleDocumentEvent();
        e.document = d;
        e.eventType = type;
        e.eventAt = LocalDateTime.now();
        e.eventBy = by;
        e.details = details == null ? null : details.length() > 1000 ? details.substring(0, 1000) : details;
        return e;
    }

    public Integer getEventId() { return eventId; }
    public WholesaleSalesDocument getDocument() { return document; }
    public String getEventType() { return eventType; }
    public LocalDateTime getEventAt() { return eventAt; }
    public String getEventBy() { return eventBy; }
    public String getDetails() { return details; }
}
