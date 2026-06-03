package com.example.bankingrisk.analyst.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AlertDetailResponse(
    UUID alertId,
    UUID transferId,
    int riskScore,
    String riskLevel,
    String status,
    boolean priority,
    Object triggeredRules,
    String maskedNarrative,
    String aiErrorCode,
    String transferStatus,
    long transferAmountMinor,
    String currency,
    List<LedgerEntrySummary> ledgerEntries,
    Instant createdAt
) {
    public record LedgerEntrySummary(
        String entryType,
        long amountMinor,
        Instant createdAt
    ) {}
}
