package com.repopilot.llm.service;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class LlmLeaseTokenGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private LlmLeaseTokenGenerator() {
    }

    public static String generateLeaseToken() {
        byte[] bytes = new byte[32]; // 256 bits of entropy
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
