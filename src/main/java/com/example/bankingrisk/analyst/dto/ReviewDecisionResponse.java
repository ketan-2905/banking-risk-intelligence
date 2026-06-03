package com.example.bankingrisk.analyst.dto;

import java.util.UUID;

public record ReviewDecisionResponse(
    UUID alertId,
    String decision,
    String alertStatus,
    String transferStatus
) {}
