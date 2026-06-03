package com.example.bankingrisk.analyst.dto;

import java.time.Instant;
import java.util.UUID;

public record AlertSummaryResponse(
    UUID alertId,
    UUID transferId,
    int riskScore,
    String riskLevel,
    String status,
    boolean priority,
    Object triggeredRules,   // deserialized from JSON; no raw PII
    String maskedNarrative,
    Instant createdAt
) {}
