package com.repopilot.github.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenEncryptionServiceTest {

    private TokenEncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        encryptionService = new TokenEncryptionServiceImpl("TestSecretEncryptionKeyForJUnit32BytesLong!");
    }

    @Test
    void testEncryptAndDecrypt() {
        String token = "gho_1234567890abcdefghijklmnopqrstuvwxyz";
        String encrypted = encryptionService.encrypt(token);

        assertNotNull(encrypted);
        assertNotEquals(token, encrypted);

        String decrypted = encryptionService.decrypt(encrypted);
        assertEquals(token, decrypted);
    }

    @Test
    void testEncryptNullOrEmpty() {
        assertNull(encryptionService.encrypt(null));
        assertNull(encryptionService.encrypt(""));
        assertNull(encryptionService.decrypt(null));
        assertNull(encryptionService.decrypt(""));
    }
}
