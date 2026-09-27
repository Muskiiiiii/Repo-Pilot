package com.repopilot.github.security;

public interface TokenEncryptionService {

    String encrypt(String plainText);

    String decrypt(String encryptedText);
}
