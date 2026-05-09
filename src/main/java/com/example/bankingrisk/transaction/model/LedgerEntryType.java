package com.example.bankingrisk.transaction.model;

public enum LedgerEntryType {
    DEBIT_AVAILABLE,
    CREDIT_AVAILABLE,
    HOLD_DEBIT,
    HOLD_RELEASE,
    HELD_TO_SETTLED_DEBIT
}
