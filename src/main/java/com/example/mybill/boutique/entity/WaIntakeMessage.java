package com.example.mybill.boutique.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One message of a forwarded post: the text or a photo (stored on GCS). Unique by WhatsApp's message id (V18). */
@Entity
@Table(name = "wa_intake_message")
public class WaIntakeMessage {

    public static final String TEXT = "text", IMAGE = "image";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Integer messageId;

    @Column(name = "wa_message_id", nullable = false, length = 100, unique = true) private String waMessageId;
    @Column(name = "batch_id") private Integer batchId;
    @Column(name = "from_number", nullable = false, length = 20) private String fromNumber;
    @Column(name = "msg_type", nullable = false, length = 10) private String msgType;
    /** Message text or photo caption. */
    @Column(name = "text") private String text;
    @Column(name = "media_id", length = 100) private String mediaId;
    @Column(name = "mime_type", length = 50) private String mimeType;
    @Column(name = "gcs_path", length = 500) private String gcsPath;
    @Column(name = "received_at", nullable = false) private LocalDateTime receivedAt;
    @Column(name = "processed", nullable = false) private Boolean processed = false;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = LocalDateTime.now();
    }

    public Integer getMessageId() { return messageId; }
    public String getWaMessageId() { return waMessageId; }
    public void setWaMessageId(String waMessageId) { this.waMessageId = waMessageId; }
    public Integer getBatchId() { return batchId; }
    public void setBatchId(Integer batchId) { this.batchId = batchId; }
    public String getFromNumber() { return fromNumber; }
    public void setFromNumber(String fromNumber) { this.fromNumber = fromNumber; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getGcsPath() { return gcsPath; }
    public void setGcsPath(String gcsPath) { this.gcsPath = gcsPath; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    public Boolean getProcessed() { return processed; }
    public void setProcessed(Boolean processed) { this.processed = processed; }
}
