package com.example.bankingrisk.risk.context;

import java.time.Instant;
import java.util.UUID;

public record RiskContext(
    UUID transferId,
    UUID ownerUserId,
    UUID sourceAccountId,
    UUID destinationAccountId,
    long amountMinor,
    String currency,
    long accountAgeDays,
    long hourlyTransferVelocityMinor,
    boolean beneficiarySeenBefore,
    boolean loginAnomalyFlag,
    Instant builtAt
) {}
