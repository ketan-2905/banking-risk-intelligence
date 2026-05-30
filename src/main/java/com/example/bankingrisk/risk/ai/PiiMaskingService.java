package com.example.bankingrisk.risk.ai;

import com.example.bankingrisk.risk.context.RiskContext;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PiiMaskingService {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(\\+?1[\\s.\\-]?)?\\(?\\d{3}\\)?[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{4}");

    private static final Pattern CARD_PATTERN =
        Pattern.compile("\\b\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}\\b");

    private static final Pattern SSN_PATTERN =
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    public MaskedPrompt maskContext(RiskContext context) {
        Map<String, String> tokenMap = buildTokenMap(context);
        String description = buildContextDescription(context, tokenMap);
        description = applyPiiPatterns(description);
        return new MaskedPrompt(description, Collections.unmodifiableMap(tokenMap));
    }

    private Map<String, String> buildTokenMap(RiskContext context) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put(context.ownerUserId().toString(), "CUSTOMER_TOKEN_1");
        tokens.put(context.transferId().toString(), "TRANSFER_TOKEN_1");
        tokens.put(context.sourceAccountId().toString(), "SOURCE_ACCOUNT_TOKEN_1");
        tokens.put(context.destinationAccountId().toString(), "DEST_ACCOUNT_TOKEN_1");
        return tokens;
    }

    private String buildContextDescription(RiskContext context, Map<String, String> tokenMap) {
        return """
               Transfer context:
                 transfer_id: %s
                 customer: %s
                 source_account: %s
                 destination_account: %s
                 amount_minor: %d
                 currency: %s
                 account_age_days: %d
                 hourly_velocity_minor: %d
                 beneficiary_seen_before: %s
                 login_anomaly_flag: %s
                 built_at: %s
               """.formatted(
                tokenMap.get(context.transferId().toString()),
                tokenMap.get(context.ownerUserId().toString()),
                tokenMap.get(context.sourceAccountId().toString()),
                tokenMap.get(context.destinationAccountId().toString()),
                context.amountMinor(),
                context.currency(),
                context.accountAgeDays(),
                context.hourlyTransferVelocityMinor(),
                context.beneficiarySeenBefore(),
                context.loginAnomalyFlag(),
                context.builtAt().toString()
        );
    }

    public String applyPiiPatterns(String text) {
        // CARD before PHONE: phone regex matches partial 10-digit sequences inside 16-digit PANs
        text = CARD_PATTERN.matcher(text).replaceAll("[REDACTED_CARD]");
        text = SSN_PATTERN.matcher(text).replaceAll("[REDACTED_SSN]");
        text = EMAIL_PATTERN.matcher(text).replaceAll("[REDACTED_EMAIL]");
        text = PHONE_PATTERN.matcher(text).replaceAll("[REDACTED_PHONE]");
        return text;
    }
}
