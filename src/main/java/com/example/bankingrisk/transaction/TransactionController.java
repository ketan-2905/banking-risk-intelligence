package com.example.bankingrisk.transaction;

import com.example.bankingrisk.security.UserPrincipal;
import com.example.bankingrisk.transaction.dto.CreateTransferRequest;
import com.example.bankingrisk.transaction.dto.CreateTransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<CreateTransferResponse> createTransfer(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal UserPrincipal principal,
        @Valid @RequestBody CreateTransferRequest request) {

        CreateTransferResponse response =
            transactionService.createTransfer(principal, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
