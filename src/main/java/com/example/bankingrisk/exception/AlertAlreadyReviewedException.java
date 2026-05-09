package com.example.bankingrisk.exception;

public class AlertAlreadyReviewedException extends RuntimeException {
    public AlertAlreadyReviewedException(String message) {
        super(message);
    }
}
