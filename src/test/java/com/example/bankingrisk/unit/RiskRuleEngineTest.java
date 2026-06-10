package com.example.bankingrisk.unit;

import com.example.bankingrisk.risk.context.RiskContext;
import com.example.bankingrisk.risk.engine.RiskEvaluation;
import com.example.bankingrisk.risk.engine.RiskRuleEngine;
import com.example.bankingrisk.risk.model.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskRuleEngineTest {

    private RiskRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RiskRuleEngine();
    }

    // ── No rules ────────────────────────────────────────────────────────────────

    @Test
    void noRulesTriggered_scoreZeroLow() {
        RiskEvaluation eval = engine.evaluate(cleanContext(100_00L, 10, 0L, true, false));
        assertThat(eval.score()).isEqualTo(0);
        assertThat(eval.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(eval.triggeredRules()).isEmpty();
    }

    // ── Individual rules ─────────────────────────────────────────────────────

    @Test
    void accountAgeUnder7_adds20() {
        RiskEvaluation eval = engine.evaluate(cleanContext(100_00L, 6, 0L, true, false));
        assertThat(eval.score()).isEqualTo(20);
        assertThat(eval.triggeredRules()).hasSize(1);
        assertThat(eval.triggeredRules().get(0).code()).isEqualTo("ACCOUNT_AGE_UNDER_7_DAYS");
        assertThat(eval.triggeredRules().get(0).points()).isEqualTo(20);
    }

    @Test
    void velocityOver5000_adds30() {
        RiskEvaluation eval = engine.evaluate(cleanContext(100_00L, 10, 500_001L, true, false));
        assertThat(eval.score()).isEqualTo(30);
        assertThat(eval.triggeredRules()).hasSize(1);
        assertThat(eval.triggeredRules().get(0).code()).isEqualTo("HOURLY_VELOCITY_OVER_5000");
        assertThat(eval.triggeredRules().get(0).points()).isEqualTo(30);
    }

    @Test
    void newBeneficiary_adds25() {
        RiskEvaluation eval = engine.evaluate(cleanContext(100_00L, 10, 0L, false, false));
        assertThat(eval.score()).isEqualTo(25);
        assertThat(eval.triggeredRules()).hasSize(1);
        assertThat(eval.triggeredRules().get(0).code()).isEqualTo("NEW_BENEFICIARY");
        assertThat(eval.triggeredRules().get(0).points()).isEqualTo(25);
    }

    @Test
    void loginAnomaly_adds16() {
        RiskEvaluation eval = engine.evaluate(cleanContext(100_00L, 10, 0L, true, true));
        assertThat(eval.score()).isEqualTo(16);
        assertThat(eval.triggeredRules()).hasSize(1);
        assertThat(eval.triggeredRules().get(0).code()).isEqualTo("LOGIN_ANOMALY");
        assertThat(eval.triggeredRules().get(0).points()).isEqualTo(16);
    }

    @Test
    void amountOver10000_adds10() {
        RiskEvaluation eval = engine.evaluate(cleanContext(1_000_001L, 10, 0L, true, false));
        assertThat(eval.score()).isEqualTo(10);
        assertThat(eval.triggeredRules()).hasSize(1);
        assertThat(eval.triggeredRules().get(0).code()).isEqualTo("AMOUNT_OVER_10000");
        assertThat(eval.triggeredRules().get(0).points()).isEqualTo(10);
    }

    // ── All rules → clamp to 100 ─────────────────────────────────────────────

    @Test
    void allRulesTriggered_scoreClampedTo100High() {
        // 20 + 30 + 25 + 16 + 10 = 101 → clamped to 100
        RiskEvaluation eval = engine.evaluate(
            new RiskContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1_000_001L,  // amount > 10000 (+10)
                "USD",
                6,           // accountAgeDays < 7 (+20)
                500_001L,    // velocity > 5000 (+30)
                false,       // new beneficiary (+25)
                true,        // login anomaly (+16)
                Instant.now()
            )
        );
        assertThat(eval.score()).isEqualTo(100);
        assertThat(eval.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(eval.triggeredRules()).hasSize(5);
    }

    // ── Boundary thresholds ──────────────────────────────────────────────────

    @Test
    void scoreBoundaries_fromScore() {
        assertThat(RiskLevel.fromScore(0)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromScore(39)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromScore(40)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromScore(79)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromScore(80)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.fromScore(100)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void velocityExactly5000_doesNotTrigger() {
        // 500000 is NOT > 500000
        RiskEvaluation eval = engine.evaluate(cleanContext(100_00L, 10, 500_000L, true, false));
        assertThat(eval.triggeredRules()).isEmpty();
    }

    @Test
    void accountAgeExactly7_doesNotTrigger() {
        // 7 is NOT < 7
        RiskEvaluation eval = engine.evaluate(cleanContext(100_00L, 7, 0L, true, false));
        assertThat(eval.triggeredRules()).isEmpty();
    }

    @Test
    void amountExactly10000_doesNotTrigger() {
        // 1000000 is NOT > 1000000
        RiskEvaluation eval = engine.evaluate(cleanContext(1_000_000L, 10, 0L, true, false));
        assertThat(eval.triggeredRules()).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RiskContext cleanContext(
            long amountMinor,
            long accountAgeDays,
            long velocityMinor,
            boolean beneficiarySeenBefore,
            boolean loginAnomalyFlag) {
        return new RiskContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            amountMinor, "USD",
            accountAgeDays, velocityMinor,
            beneficiarySeenBefore, loginAnomalyFlag,
            Instant.now()
        );
    }
}
