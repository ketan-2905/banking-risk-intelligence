package com.example.bankingrisk.local;

import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.model.Account;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@Profile("local")
@RequestMapping("/dev")
class DevDemoController {

    private final JdbcTemplate jdbc;
    private final LocalDataSeeder seeder;
    private final AccountRepository accountRepository;

    DevDemoController(JdbcTemplate jdbc,
                      LocalDataSeeder seeder,
                      AccountRepository accountRepository) {
        this.jdbc = jdbc;
        this.seeder = seeder;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/reset")
    ResponseEntity<Map<String, String>> reset() {
        jdbc.execute(
            "TRUNCATE TABLE risk_review_audits, ledger_entries, risk_alerts, transfers, " +
            "beneficiaries, login_audits, accounts CASCADE"
        );
        seeder.seedData();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "All data cleared and re-seeded"));
    }

    @GetMapping("/accounts")
    ResponseEntity<List<Map<String, Object>>> accounts() {
        List<Map<String, Object>> result = new ArrayList<>();
        accountRepository.findByOwnerUserId(LocalDataSeeder.USER_1_ID)
            .ifPresent(a -> result.add(toMap("customer1", a)));
        accountRepository.findByOwnerUserId(LocalDataSeeder.USER_2_ID)
            .ifPresent(a -> result.add(toMap("customer2", a)));
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toMap(String label, Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("accountId", a.getId().toString());
        m.put("accountNumber", a.getAccountNumber());
        m.put("availableBalanceMinor", a.getAvailableBalanceMinor());
        m.put("heldBalanceMinor", a.getHeldBalanceMinor());
        m.put("currency", a.getCurrency());
        return m;
    }
}
