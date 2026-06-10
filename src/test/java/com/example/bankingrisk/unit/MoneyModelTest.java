package com.example.bankingrisk.unit;

import com.example.bankingrisk.transaction.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MoneyModelTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setAvailableBalanceMinor(10_000_00L); // $10,000.00
        account.setHeldBalanceMinor(0L);
    }

    // --- hold() ---

    @Test
    void hold_movesAvailableToHeld() {
        account.hold(3_000_00L);

        assertThat(account.getAvailableBalanceMinor()).isEqualTo(7_000_00L);
        assertThat(account.getHeldBalanceMinor()).isEqualTo(3_000_00L);
    }

    @Test
    void hold_exactBalance_succeeds() {
        account.hold(10_000_00L);

        assertThat(account.getAvailableBalanceMinor()).isZero();
        assertThat(account.getHeldBalanceMinor()).isEqualTo(10_000_00L);
    }

    @Test
    void hold_insufficientFunds_throws() {
        assertThatThrownBy(() -> account.hold(10_000_01L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Insufficient available balance for hold");
    }

    @Test
    void hold_negativeAmount_throws() {
        assertThatThrownBy(() -> account.hold(-1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");
    }

    // --- releaseHold() ---

    @Test
    void releaseHold_movesHeldToAvailable() {
        account.hold(5_000_00L);
        account.releaseHold(2_000_00L);

        assertThat(account.getAvailableBalanceMinor()).isEqualTo(7_000_00L);
        assertThat(account.getHeldBalanceMinor()).isEqualTo(3_000_00L);
    }

    @Test
    void releaseHold_moreThanHeld_throws() {
        account.hold(1_000_00L);

        assertThatThrownBy(() -> account.releaseHold(2_000_00L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot release more than held balance");
    }

    @Test
    void releaseHold_negativeAmount_throws() {
        assertThatThrownBy(() -> account.releaseHold(-1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- debitAvailable() ---

    @Test
    void debitAvailable_reducesBalance() {
        account.debitAvailable(1_500_00L);

        assertThat(account.getAvailableBalanceMinor()).isEqualTo(8_500_00L);
    }

    @Test
    void debitAvailable_insufficientFunds_throws() {
        assertThatThrownBy(() -> account.debitAvailable(10_000_01L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Insufficient available balance for debit");
    }

    @Test
    void debitAvailable_negativeAmount_throws() {
        assertThatThrownBy(() -> account.debitAvailable(-1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- creditAvailable() ---

    @Test
    void creditAvailable_increasesBalance() {
        account.creditAvailable(5_00L);

        assertThat(account.getAvailableBalanceMinor()).isEqualTo(10_005_00L);
    }

    @Test
    void creditAvailable_negativeAmount_throws() {
        assertThatThrownBy(() -> account.creditAvailable(-1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- RiskLevel mapping ---

    @Test
    void riskLevel_fromScore_low() {
        assertThat(com.example.bankingrisk.risk.model.RiskLevel.fromScore(0)).isEqualTo(com.example.bankingrisk.risk.model.RiskLevel.LOW);
        assertThat(com.example.bankingrisk.risk.model.RiskLevel.fromScore(39)).isEqualTo(com.example.bankingrisk.risk.model.RiskLevel.LOW);
    }

    @Test
    void riskLevel_fromScore_medium() {
        assertThat(com.example.bankingrisk.risk.model.RiskLevel.fromScore(40)).isEqualTo(com.example.bankingrisk.risk.model.RiskLevel.MEDIUM);
        assertThat(com.example.bankingrisk.risk.model.RiskLevel.fromScore(79)).isEqualTo(com.example.bankingrisk.risk.model.RiskLevel.MEDIUM);
    }

    @Test
    void riskLevel_fromScore_high() {
        assertThat(com.example.bankingrisk.risk.model.RiskLevel.fromScore(80)).isEqualTo(com.example.bankingrisk.risk.model.RiskLevel.HIGH);
        assertThat(com.example.bankingrisk.risk.model.RiskLevel.fromScore(100)).isEqualTo(com.example.bankingrisk.risk.model.RiskLevel.HIGH);
    }
}
