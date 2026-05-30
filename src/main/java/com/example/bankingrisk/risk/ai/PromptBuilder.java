package com.example.bankingrisk.risk.ai;

import com.example.bankingrisk.risk.engine.RiskEvaluation;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PromptBuilder {

    private static final String SYSTEM_INSTRUCTION =
        "You are a fraud analyst assistant. Provide a concise, evidence-based risk assessment. " +
        "Do not make unsupported claims. Only cite evidence present in the provided context. " +
        "Do not speculate about customer identity or real-world individuals.";

    private static final String OUTPUT_FORMAT =
        """
        Risk Summary: <2-3 sentences>
        Likely Pattern: <short label>
        Key Evidence:
        - <evidence 1>
        - <evidence 2>
        Recommended Analyst Action: <approve/review/reject with one-sentence reason>""";

    private static String applyTokenMap(String text, Map<String, String> tokenMap) {
        for (Map.Entry<String, String> entry : tokenMap.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    public String buildPrompt(MaskedPrompt maskedContext, RiskEvaluation evaluation) {
        String rulesText = evaluation.triggeredRules().stream()
            .map(r -> "  - %s (+%d pts): %s".formatted(r.code(), r.points(),
                applyTokenMap(r.evidence(), maskedContext.tokenMap())))
            .collect(Collectors.joining("\n"));

        return """
               [SYSTEM]
               %s

               [RISK ASSESSMENT INPUT]
               Risk Level: %s
               Risk Score: %d / 100

               Triggered Rules:
               %s

               Context Snapshot:
               %s
               [OUTPUT FORMAT]
               Respond using exactly this structure:
               %s
               """.formatted(
                SYSTEM_INSTRUCTION,
                evaluation.riskLevel().name(),
                evaluation.score(),
                rulesText.isEmpty() ? "  (none)" : rulesText,
                maskedContext.maskedContextDescription(),
                OUTPUT_FORMAT
        );
    }
}
