package com.example.bankingrisk.risk.model;

public enum RiskAlertStatus {
    LOW_NO_AI,
    AI_PENDING,
    AI_COMPLETE,
    REVIEW_PENDING,
    APPROVED,
    REJECTED,
    ERROR
}
