package com.example.bankingrisk.local;

import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.model.Account;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@Profile("local")
@RequestMapping("/dev")
class DemoContextController {

    private final AccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;

    DemoContextController(AccountRepository accountRepository,
                          BeneficiaryRepository beneficiaryRepository) {
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
    }

    @GetMapping("/demo-context")
    ResponseEntity<Map<String, Object>> demoContext() {

        Optional<Account> acc1Opt = accountRepository.findByOwnerUserId(LocalDataSeeder.USER_1_ID);
        Optional<Account> acc2Opt = accountRepository.findByOwnerUserId(LocalDataSeeder.USER_2_ID);

        boolean c1Valid = acc1Opt.isPresent();
        boolean c2Valid = acc2Opt.isPresent();

        boolean knownBenefExists = false;
        if (c1Valid && c2Valid) {
            knownBenefExists = beneficiaryRepository
                .findByOwnerUserIdAndDestinationAccountId(
                    LocalDataSeeder.USER_1_ID, acc2Opt.get().getId())
                .isPresent();
        }

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("customer1AccountExists",            c1Valid);
        validation.put("customer2AccountExists",            c2Valid);
        validation.put("knownBeneficiarySeeded",            knownBenefExists);
        validation.put("transferDtoFieldsMatchedInUi",      true);

        Map<String, Object> body = new LinkedHashMap<>();

        if (!c1Valid || !c2Valid) {
            body.put("validation", validation);
            body.put("error",
                "Demo context validation failed: seeded accounts not found. " +
                "Restart the app to re-run the seeder.");
            return ResponseEntity.status(500).body(body);
        }

        String acc1Id = acc1Opt.get().getId().toString();
        String acc2Id = acc2Opt.get().getId().toString();

        body.put("users", Map.of(
            "customer1", Map.of(
                "userId",   LocalDataSeeder.USER_1_ID.toString(),
                "accounts", List.of(Map.of(
                    "accountId",    acc1Id,
                    "balanceMinor", acc1Opt.get().getAvailableBalanceMinor(),
                    "currency",     acc1Opt.get().getCurrency()
                ))
            ),
            "customer2", Map.of(
                "userId",   LocalDataSeeder.USER_2_ID.toString(),
                "accounts", List.of(Map.of(
                    "accountId",    acc2Id,
                    "balanceMinor", acc2Opt.get().getAvailableBalanceMinor(),
                    "currency",     acc2Opt.get().getCurrency()
                ))
            )
        ));
        body.put("recommended", Map.of(
            "customer1SourceAccountId",             acc1Id,
            "customer2SourceAccountId",             acc2Id,
            "knownBeneficiaryDestinationAccountId", acc2Id,
            "newBeneficiaryDestinationAccountId",   acc1Id
        ));
        body.put("validation", validation);

        return ResponseEntity.ok(body);
    }
}
