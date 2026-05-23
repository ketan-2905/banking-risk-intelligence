package com.example.bankingrisk.risk.model;

public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static RiskLevel fromScore(int score) {
        if (score >= 80) return HIGH;
        if (score >= 40) return MEDIUM;
        return LOW;
    }
}
