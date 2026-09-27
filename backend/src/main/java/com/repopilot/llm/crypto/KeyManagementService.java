package com.repopilot.llm.crypto;

public interface KeyManagementService {
    /**
     * Generates a fresh 256-bit Data Encryption Key (DEK).
     */
    byte[] generateDataEncryptionKey();

    /**
     * Encrypts/wraps the plaintext DEK using the KEK for keyVersion.
     */
    byte[] wrapDataEncryptionKey(byte[] plainDek, int keyVersion);

    /**
     * Decrypts/unwraps the encrypted DEK using the KEK for keyVersion.
     */
    byte[] unwrapDataEncryptionKey(byte[] encryptedDek, int keyVersion);

    /**
     * Rewraps an encrypted DEK from oldKeyVersion to newKeyVersion.
     */
    byte[] rewrapDataEncryptionKey(byte[] encryptedDek, int oldKeyVersion, int newKeyVersion);

    /**
     * Returns the active primary KEK version.
     */
    int getCurrentKeyVersion();

    /**
     * Registers a new KEK version (simulating KMS key creation/rotation).
     */
    void registerKeyVersion(int version, byte[] kek32Bytes);

    /**
     * Resets KMS to default test keys.
     */
    void resetToDefaultKeys();
}
