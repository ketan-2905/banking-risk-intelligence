package com.example.bankingrisk.risk.event;

import com.example.bankingrisk.risk.context.RiskContext;
import com.example.bankingrisk.risk.engine.RiskEvaluation;

public record TransferRiskEvaluatedEvent(
    RiskContext context,
    RiskEvaluation evaluation
) {}
