package com.example.bankingrisk.transaction.dto;

import java.util.UUID;

public record CreateTransferResponse(
    UUID transferId,
    String status,
    int riskScore,
    String riskLevel,
    String message
) {}
