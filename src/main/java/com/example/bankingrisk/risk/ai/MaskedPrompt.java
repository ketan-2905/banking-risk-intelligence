package com.example.bankingrisk.risk.ai;

import java.util.Map;

public record MaskedPrompt(
    String maskedContextDescription,
    Map<String, String> tokenMap  // in-memory only — never persisted or logged
) {}
