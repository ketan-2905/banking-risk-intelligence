package com.example.bankingrisk.exception;

import java.util.UUID;

public class AlertNotFoundException extends RuntimeException {
    public AlertNotFoundException(UUID alertId) {
        super("Alert not found: " + alertId);
    }
}
