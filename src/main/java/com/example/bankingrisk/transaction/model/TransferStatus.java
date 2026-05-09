package com.example.bankingrisk.transaction.model;

public enum TransferStatus {
    INITIATED,
    POSTED,
    PENDING_REVIEW,
    APPROVED_SETTLED,
    REJECTED_RELEASED,
    FAILED
}
