package com.example.bankingrisk.local;

import com.example.bankingrisk.transaction.AccountRepository;
import com.example.bankingrisk.transaction.BeneficiaryRepository;
import com.example.bankingrisk.transaction.LoginAuditRepository;
import com.example.bankingrisk.transaction.model.Account;
import com.example.bankingrisk.transaction.model.Beneficiary;
import com.example.bankingrisk.transaction.model.LoginAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Inserts synthetic demo data for local development only.
 * Never active in prod or test profiles. No real PII.
 */
@Component
@Profile("local")
class LocalDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDataSeeder.class);

    static final UUID USER_1_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID ACCOUNT_1_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final UUID ACCOUNT_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");

    private final AccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final LoginAuditRepository loginAuditRepository;

    LocalDataSeeder(AccountRepository accountRepository,
                    BeneficiaryRepository beneficiaryRepository,
                    LoginAuditRepository loginAuditRepository) {
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.loginAuditRepository = loginAuditRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accountRepository.existsById(ACCOUNT_1_ID)) {
            log.info("Local seed data already present, skipping");
            return;
        }

        log.info("Inserting local synthetic seed data");

        Account account1 = new Account();
        account1.setId(ACCOUNT_1_ID);
        account1.setOwnerUserId(USER_1_ID);
        account1.setAccountNumber("DEMO-ACC-0001");
        account1.setCurrency("USD");
        account1.setAvailableBalanceMinor(500_000_00L); // $500,000.00
        account1.setHeldBalanceMinor(0L);
        accountRepository.save(account1);

        Account account2 = new Account();
        account2.setId(ACCOUNT_2_ID);
        account2.setOwnerUserId(USER_2_ID);
        account2.setAccountNumber("DEMO-ACC-0002");
        account2.setCurrency("USD");
        account2.setAvailableBalanceMinor(100_000_00L); // $100,000.00
        account2.setHeldBalanceMinor(0L);
        accountRepository.save(account2);

        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setOwnerUserId(USER_1_ID);
        beneficiary.setDestinationAccountId(ACCOUNT_2_ID);
        beneficiaryRepository.save(beneficiary);

        LoginAudit normalLogin = new LoginAudit();
        normalLogin.setUserId(USER_1_ID);
        normalLogin.setIpAddressHash("hash-of-192-0-2-1");
        normalLogin.setDeviceFingerprintHash("hash-of-device-A");
        normalLogin.setAnomalyFlag(false);
        normalLogin.setCreatedAt(Instant.now().minusSeconds(3600));
        loginAuditRepository.save(normalLogin);

        LoginAudit anomalyLogin = new LoginAudit();
        anomalyLogin.setUserId(USER_2_ID);
        anomalyLogin.setIpAddressHash("hash-of-198-51-100-1");
        anomalyLogin.setDeviceFingerprintHash("hash-of-device-B");
        anomalyLogin.setAnomalyFlag(true);
        anomalyLogin.setCreatedAt(Instant.now().minusSeconds(1800));
        loginAuditRepository.save(anomalyLogin);

        log.info("Local seed data inserted: 2 accounts, 1 beneficiary, 2 login audits");
    }
}
