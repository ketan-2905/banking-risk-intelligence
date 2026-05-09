package com.example.bankingrisk.transaction.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_audits")
public class LoginAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "ip_address_hash")
    private String ipAddressHash;

    @Column(name = "device_fingerprint_hash")
    private String deviceFingerprintHash;

    @Column(name = "anomaly_flag", nullable = false)
    private boolean anomalyFlag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getIpAddressHash() { return ipAddressHash; }
    public String getDeviceFingerprintHash() { return deviceFingerprintHash; }
    public boolean isAnomalyFlag() { return anomalyFlag; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(UUID id) { this.id = id; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setIpAddressHash(String ipAddressHash) { this.ipAddressHash = ipAddressHash; }
    public void setDeviceFingerprintHash(String deviceFingerprintHash) { this.deviceFingerprintHash = deviceFingerprintHash; }
    public void setAnomalyFlag(boolean anomalyFlag) { this.anomalyFlag = anomalyFlag; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
