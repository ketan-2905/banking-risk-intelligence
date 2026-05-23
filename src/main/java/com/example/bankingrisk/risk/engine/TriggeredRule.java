package com.example.bankingrisk.risk.engine;

public record TriggeredRule(
    String code,
    String description,
    int points,
    String evidence
) {}
