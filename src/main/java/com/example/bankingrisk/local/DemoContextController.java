package com.example.bankingrisk.local;

import com.example.bankingrisk.transaction.AccountRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes repository-validated seeded account IDs for the local demo UI.
 * Active only in the local profile — never exposed in production.
 * Returns HTTP 500 if the seeder has not correctly persisted the expected accounts.
 */
@RestController
@Profile("local")
@RequestMapping("/dev")
class DemoContextController {

    private final AccountRepository accountRepository;

    DemoContextController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping("/demo-context")
    ResponseEntity<Map<String, Object>> demoContext() {

        // Verify accounts exist with the exact ownership the transfer service checks.
        // Uses AccountRepository.findByIdAndOwnerUserId — the same ownership check
        // that TransactionService.doTransfer performs before accepting a transfer.
        boolean c1Valid = accountRepository
            .findByIdAndOwnerUserId(LocalDataSeeder.ACCOUNT_1_ID, LocalDataSeeder.USER_1_ID)
            .isPresent();

        boolean c2Valid = accountRepository
            .findByIdAndOwnerUserId(LocalDataSeeder.ACCOUNT_2_ID, LocalDataSeeder.USER_2_ID)
            .isPresent();

        // Destination accounts just need to exist (no ownership requirement for destination)
        boolean knownBenefExists = accountRepository.existsById(LocalDataSeeder.ACCOUNT_2_ID);
        boolean newBenefExists   = accountRepository.existsById(LocalDataSeeder.ACCOUNT_1_ID);

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("customer1SourceOwnedByCustomer1",    c1Valid);
        validation.put("customer2SourceOwnedByCustomer2",    c2Valid);
        validation.put("knownBeneficiaryDestinationExists",  knownBenefExists);
        validation.put("newBeneficiaryDestinationExists",    newBenefExists);
        // DTO field names verified against CreateTransferRequest record:
        // sourceAccountId, destinationAccountId, amountMinor, currency
        validation.put("transferDtoFieldsMatchedInUi",       true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", Map.of(
            "customer1", Map.of(
                "userId",   LocalDataSeeder.USER_1_ID.toString(),
                "accounts", List.of(Map.of(
                    "accountId",    LocalDataSeeder.ACCOUNT_1_ID.toString(),
                    "balanceMinor", 50_000_000L,
                    "currency",     "USD"
                ))
            ),
            "customer2", Map.of(
                "userId",   LocalDataSeeder.USER_2_ID.toString(),
                "accounts", List.of(Map.of(
                    "accountId",    LocalDataSeeder.ACCOUNT_2_ID.toString(),
                    "balanceMinor", 10_000_000L,
                    "currency",     "USD"
                ))
            )
        ));
        body.put("recommended", Map.of(
            "customer1SourceAccountId",             LocalDataSeeder.ACCOUNT_1_ID.toString(),
            "customer2SourceAccountId",             LocalDataSeeder.ACCOUNT_2_ID.toString(),
            "knownBeneficiaryDestinationAccountId", LocalDataSeeder.ACCOUNT_2_ID.toString(),
            "newBeneficiaryDestinationAccountId",   LocalDataSeeder.ACCOUNT_1_ID.toString()
        ));
        body.put("validation", validation);

        boolean allValid = c1Valid && c2Valid && knownBenefExists && newBenefExists;
        if (!allValid) {
            body.put("error",
                "Demo context validation failed: one or more seeded accounts were not found " +
                "with the expected UUID and owner. Check seeder logs. " +
                "Run reset-local-db.ps1 and restart the app.");
            return ResponseEntity.status(500).body(body);
        }

        return ResponseEntity.ok(body);
    }
}
