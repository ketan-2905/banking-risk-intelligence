package com.example.bankingrisk.risk.engine;

import com.example.bankingrisk.risk.model.RiskLevel;

import java.util.List;

public record RiskEvaluation(
    int score,
    RiskLevel riskLevel,
    List<TriggeredRule> triggeredRules
) {}
