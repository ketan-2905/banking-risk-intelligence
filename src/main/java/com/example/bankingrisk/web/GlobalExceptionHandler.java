package com.example.bankingrisk.web;

import com.example.bankingrisk.exception.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import com.example.bankingrisk.transaction.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingIdempotencyKey(MissingIdempotencyKeyException e) {
        return ResponseEntity.badRequest()
            .body(new ApiErrorResponse("MISSING_IDEMPOTENCY_KEY", e.getMessage()));
    }

    @ExceptionHandler(InvalidTransferRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(InvalidTransferRequestException e) {
        return ResponseEntity.badRequest()
            .body(new ApiErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
            .body(new ApiErrorResponse("VALIDATION_ERROR", details));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiErrorResponse("INSUFFICIENT_FUNDS", e.getMessage()));
    }

    @ExceptionHandler(RequestInProgressException.class)
    public ResponseEntity<ApiErrorResponse> handleInProgress(RequestInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiErrorResponse("REQUEST_IN_PROGRESS", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenAccountException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(ForbiddenAccountException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ApiErrorResponse("FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(new ApiErrorResponse("RATE_LIMIT_EXCEEDED", e.getMessage()));
    }

    @ExceptionHandler(AlertAlreadyReviewedException.class)
    public ResponseEntity<ApiErrorResponse> handleAlreadyReviewed(AlertAlreadyReviewedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiErrorResponse("ALERT_ALREADY_REVIEWED", e.getMessage()));
    }

    @ExceptionHandler(AlertNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAlertNotFound(AlertNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiErrorResponse("ALERT_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error processing request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
