package com.example.bankingrisk.risk.engine;

import com.example.bankingrisk.risk.context.RiskContext;
import com.example.bankingrisk.risk.model.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RiskRuleEngine {

    public RiskEvaluation evaluate(RiskContext context) {
        List<TriggeredRule> triggered = new ArrayList<>();

        if (context.accountAgeDays() < 7) {
            triggered.add(new TriggeredRule(
                "ACCOUNT_AGE_UNDER_7_DAYS",
                "Account is less than 7 days old",
                20,
                "accountAgeDays=" + context.accountAgeDays()
            ));
        }

        if (context.hourlyTransferVelocityMinor() > 500_000) {
            triggered.add(new TriggeredRule(
                "HOURLY_VELOCITY_OVER_5000",
                "Hourly outgoing transfer velocity exceeds 5000 USD",
                30,
                "velocityMinor=" + context.hourlyTransferVelocityMinor()
            ));
        }

        if (!context.beneficiarySeenBefore()) {
            triggered.add(new TriggeredRule(
                "NEW_BENEFICIARY",
                "Destination has not been seen as a beneficiary before",
                25,
                "destinationAccountId=" + context.destinationAccountId()
            ));
        }

        if (context.loginAnomalyFlag()) {
            triggered.add(new TriggeredRule(
                "LOGIN_ANOMALY",
                "Login anomaly detected in last 24 hours",
                16,
                "userId=" + context.ownerUserId()
            ));
        }

        if (context.amountMinor() > 1_000_000) {
            triggered.add(new TriggeredRule(
                "AMOUNT_OVER_10000",
                "Transfer amount exceeds 10000 USD",
                10,
                "amountMinor=" + context.amountMinor()
            ));
        }

        int rawScore = triggered.stream().mapToInt(TriggeredRule::points).sum();
        int score = Math.min(rawScore, 100);
        RiskLevel level = RiskLevel.fromScore(score);

        return new RiskEvaluation(score, level, List.copyOf(triggered));
    }
}
