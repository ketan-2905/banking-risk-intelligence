package com.example.bankingrisk.risk.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_review_audits")
public class RiskReviewAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "analyst_user_id", nullable = false)
    private UUID analystUserId;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getAlertId() { return alertId; }
    public UUID getTransferId() { return transferId; }
    public UUID getAnalystUserId() { return analystUserId; }
    public String getDecision() { return decision; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }

    public void setAlertId(UUID alertId) { this.alertId = alertId; }
    public void setTransferId(UUID transferId) { this.transferId = transferId; }
    public void setAnalystUserId(UUID analystUserId) { this.analystUserId = analystUserId; }
    public void setDecision(String decision) { this.decision = decision; }
    public void setReason(String reason) { this.reason = reason; }
}
