package com.example.bankingrisk.exception;

public class ForbiddenAccountException extends RuntimeException {
    public ForbiddenAccountException(String message) {
        super(message);
    }
}
