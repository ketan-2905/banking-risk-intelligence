package com.example.bankingrisk.risk.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_alerts")
public class RiskAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Column(name = "triggered_rules_json", nullable = false, columnDefinition = "TEXT")
    private String triggeredRulesJson = "[]";

    @Column(name = "context_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String contextSnapshotJson = "{}";

    @Column(name = "masked_narrative", columnDefinition = "TEXT")
    private String maskedNarrative;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private RiskAlertStatus status;

    @Column(name = "priority", nullable = false)
    private boolean priority;

    @Column(name = "ai_error_code", length = 100)
    private String aiErrorCode;

    @Column(name = "ai_attempt_count", nullable = false)
    private int aiAttemptCount = 0;

    @Column(name = "ai_completed_at")
    private Instant aiCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public int getRiskScore() { return riskScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getTriggeredRulesJson() { return triggeredRulesJson; }
    public String getContextSnapshotJson() { return contextSnapshotJson; }
    public String getMaskedNarrative() { return maskedNarrative; }
    public RiskAlertStatus getStatus() { return status; }
    public boolean isPriority() { return priority; }
    public String getAiErrorCode() { return aiErrorCode; }
    public int getAiAttemptCount() { return aiAttemptCount; }
    public Instant getAiCompletedAt() { return aiCompletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(UUID id) { this.id = id; }
    public void setTransferId(UUID transferId) { this.transferId = transferId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public void setTriggeredRulesJson(String triggeredRulesJson) { this.triggeredRulesJson = triggeredRulesJson; }
    public void setContextSnapshotJson(String contextSnapshotJson) { this.contextSnapshotJson = contextSnapshotJson; }
    public void setMaskedNarrative(String maskedNarrative) { this.maskedNarrative = maskedNarrative; }
    public void setStatus(RiskAlertStatus status) { this.status = status; }
    public void setPriority(boolean priority) { this.priority = priority; }
    public void setAiErrorCode(String aiErrorCode) { this.aiErrorCode = aiErrorCode; }
    public void setAiAttemptCount(int aiAttemptCount) { this.aiAttemptCount = aiAttemptCount; }
    public void setAiCompletedAt(Instant aiCompletedAt) { this.aiCompletedAt = aiCompletedAt; }
}
