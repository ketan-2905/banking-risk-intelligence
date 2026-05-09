package com.example.bankingrisk.exception;

public class InvalidTransferRequestException extends RuntimeException {
    public InvalidTransferRequestException(String message) {
        super(message);
    }
}
