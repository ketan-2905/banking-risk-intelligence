package com.example.bankingrisk.unit;

import com.example.bankingrisk.risk.ai.MaskedPrompt;
import com.example.bankingrisk.risk.ai.PromptBuilder;
import com.example.bankingrisk.risk.engine.RiskEvaluation;
import com.example.bankingrisk.risk.engine.TriggeredRule;
import com.example.bankingrisk.risk.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    void prompt_contains_risk_score_and_level() {
        MaskedPrompt masked = new MaskedPrompt("context: SOURCE_ACCOUNT_TOKEN_1", Map.of());
        RiskEvaluation eval = new RiskEvaluation(65, RiskLevel.MEDIUM,
            List.of(new TriggeredRule("NEW_BENEFICIARY", "New beneficiary", 25, "no prior transfer")));

        String prompt = builder.buildPrompt(masked, eval);

        assertThat(prompt).contains("65").contains("MEDIUM");
    }

    @Test
    void prompt_contains_triggered_rules() {
        MaskedPrompt masked = new MaskedPrompt("context: SOURCE_ACCOUNT_TOKEN_1", Map.of());
        RiskEvaluation eval = new RiskEvaluation(65, RiskLevel.MEDIUM,
            List.of(
                new TriggeredRule("NEW_BENEFICIARY", "New beneficiary", 25, "no prior transfer"),
                new TriggeredRule("ACCOUNT_AGE_UNDER_7_DAYS", "New account", 20, "account age 2 days")
            ));

        String prompt = builder.buildPrompt(masked, eval);

        assertThat(prompt).contains("NEW_BENEFICIARY").contains("ACCOUNT_AGE_UNDER_7_DAYS");
    }

    @Test
    void prompt_does_not_contain_raw_pii() {
        UUID rawId = UUID.randomUUID();
        // The masked context should only expose tokens, not the raw UUID
        MaskedPrompt masked = new MaskedPrompt(
            "context: SOURCE_ACCOUNT_TOKEN_1",
            Map.of(rawId.toString(), "SOURCE_ACCOUNT_TOKEN_1")
        );
        RiskEvaluation eval = new RiskEvaluation(65, RiskLevel.MEDIUM, List.of());

        String prompt = builder.buildPrompt(masked, eval);

        assertThat(prompt).doesNotContain(rawId.toString());
    }

    @Test
    void prompt_asks_for_required_output_format() {
        MaskedPrompt masked = new MaskedPrompt("context: CUSTOMER_TOKEN_1", Map.of());
        RiskEvaluation eval = new RiskEvaluation(90, RiskLevel.HIGH,
            List.of(new TriggeredRule("LOGIN_ANOMALY", "Login anomaly", 16, "unusual location")));

        String prompt = builder.buildPrompt(masked, eval);

        assertThat(prompt)
            .contains("Risk Summary:")
            .contains("Likely Pattern:")
            .contains("Key Evidence:")
            .contains("Recommended Analyst Action:");
    }

    @Test
    void prompt_contains_masked_context() {
        MaskedPrompt masked = new MaskedPrompt("context: CUSTOMER_TOKEN_1\namount_minor: 50000", Map.of());
        RiskEvaluation eval = new RiskEvaluation(40, RiskLevel.MEDIUM, List.of());

        String prompt = builder.buildPrompt(masked, eval);

        assertThat(prompt).contains("CUSTOMER_TOKEN_1").contains("amount_minor: 50000");
    }
}
