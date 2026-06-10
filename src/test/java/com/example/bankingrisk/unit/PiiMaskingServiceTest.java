package com.example.bankingrisk.unit;

import com.example.bankingrisk.risk.ai.MaskedPrompt;
import com.example.bankingrisk.risk.ai.PiiMaskingService;
import com.example.bankingrisk.risk.context.RiskContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingServiceTest {

    private final PiiMaskingService service = new PiiMaskingService();

    @Test
    void masks_email() {
        String result = service.applyPiiPatterns("Customer contact: alice@example.com for fraud review.");
        assertThat(result).doesNotContain("alice@example.com")
                          .contains("[REDACTED_EMAIL]");
    }

    @Test
    void masks_phone() {
        String result = service.applyPiiPatterns("Callback: +1-800-555-1234 requested.");
        assertThat(result).doesNotContain("800-555-1234")
                          .contains("[REDACTED_PHONE]");
    }

    @Test
    void masks_card_number() {
        String result = service.applyPiiPatterns("Card used: 4111111111111111 declined.");
        assertThat(result).doesNotContain("4111111111111111")
                          .contains("[REDACTED_CARD]");
    }

    @Test
    void replaces_internal_ids_with_tokens() {
        UUID ownerId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();

        RiskContext context = new RiskContext(
            transferId, ownerId, srcId, dstId,
            5_000L, "USD", 10L, 5_000L, true, false, Instant.now()
        );

        MaskedPrompt mp = service.maskContext(context);

        assertThat(mp.maskedContextDescription())
            .contains("CUSTOMER_TOKEN_1")
            .contains("SOURCE_ACCOUNT_TOKEN_1")
            .contains("DEST_ACCOUNT_TOKEN_1")
            .contains("TRANSFER_TOKEN_1");

        assertThat(mp.tokenMap())
            .containsValue("CUSTOMER_TOKEN_1")
            .containsValue("SOURCE_ACCOUNT_TOKEN_1")
            .containsValue("DEST_ACCOUNT_TOKEN_1");
    }

    @Test
    void does_not_include_raw_pii_in_prompt() {
        UUID ownerId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();

        RiskContext context = new RiskContext(
            transferId, ownerId, srcId, dstId,
            9_999L, "USD", 3L, 9_999L, false, true, Instant.now()
        );

        MaskedPrompt mp = service.maskContext(context);

        assertThat(mp.maskedContextDescription())
            .doesNotContain(ownerId.toString())
            .doesNotContain(srcId.toString())
            .doesNotContain(dstId.toString())
            .doesNotContain(transferId.toString());
    }
}
