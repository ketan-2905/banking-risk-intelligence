package com.example.bankingrisk.transaction.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 50)
    private LedgerEntryType entryType;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "description")
    private String description;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public UUID getAccountId() { return accountId; }
    public LedgerEntryType getEntryType() { return entryType; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDescription() { return description; }

    public void setId(UUID id) { this.id = id; }
    public void setTransferId(UUID transferId) { this.transferId = transferId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public void setEntryType(LedgerEntryType entryType) { this.entryType = entryType; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setDescription(String description) { this.description = description; }
}
