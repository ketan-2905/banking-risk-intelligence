package com.example.bankingrisk.transaction.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "available_balance_minor", nullable = false)
    private long availableBalanceMinor;

    @Column(name = "held_balance_minor", nullable = false)
    private long heldBalanceMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Money mutation methods — all amounts are in minor units

    public void hold(long amountMinor) {
        requireNonNegative(amountMinor);
        if (amountMinor > availableBalanceMinor) {
            throw new IllegalStateException(
                "Insufficient available balance for hold: available=%d, requested=%d"
                    .formatted(availableBalanceMinor, amountMinor));
        }
        availableBalanceMinor -= amountMinor;
        heldBalanceMinor += amountMinor;
    }

    public void settleHeld(long amountMinor) {
        requireNonNegative(amountMinor);
        if (amountMinor > heldBalanceMinor) {
            throw new IllegalStateException(
                "Cannot settle more than held balance: held=%d, requested=%d"
                    .formatted(heldBalanceMinor, amountMinor));
        }
        heldBalanceMinor -= amountMinor;
        // available balance is not increased — funds are permanently settled/debited
    }

    public void releaseHold(long amountMinor) {
        requireNonNegative(amountMinor);
        if (amountMinor > heldBalanceMinor) {
            throw new IllegalStateException(
                "Cannot release more than held balance: held=%d, requested=%d"
                    .formatted(heldBalanceMinor, amountMinor));
        }
        heldBalanceMinor -= amountMinor;
        availableBalanceMinor += amountMinor;
    }

    public void debitAvailable(long amountMinor) {
        requireNonNegative(amountMinor);
        if (amountMinor > availableBalanceMinor) {
            throw new IllegalStateException(
                "Insufficient available balance for debit: available=%d, requested=%d"
                    .formatted(availableBalanceMinor, amountMinor));
        }
        availableBalanceMinor -= amountMinor;
    }

    public void creditAvailable(long amountMinor) {
        requireNonNegative(amountMinor);
        availableBalanceMinor += amountMinor;
    }

    private void requireNonNegative(long amountMinor) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("Amount must not be negative, got: " + amountMinor);
        }
    }

    // Getters

    public UUID getId() { return id; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public String getAccountNumber() { return accountNumber; }
    public String getCurrency() { return currency; }
    public long getAvailableBalanceMinor() { return availableBalanceMinor; }
    public long getHeldBalanceMinor() { return heldBalanceMinor; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    // Setters

    public void setId(UUID id) { this.id = id; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setAvailableBalanceMinor(long availableBalanceMinor) { this.availableBalanceMinor = availableBalanceMinor; }
    public void setHeldBalanceMinor(long heldBalanceMinor) { this.heldBalanceMinor = heldBalanceMinor; }
}
