package com.repopilot.llm.crypto;

import com.repopilot.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocalKmsServiceImpl implements KeyManagementService {

    private static final int DEK_SIZE_BYTES = 32;
    private static final int GCM_NONCE_SIZE_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Integer, SecretKey> kekMap = new ConcurrentHashMap<>();
    private volatile int currentKeyVersion = 1;

    public LocalKmsServiceImpl() {
        // Initialize default KEK version 1 and version 2 for key rotation support
        initializeKek(1, "RepoPilotKekMasterVersion1Key32B");
        initializeKek(2, "RepoPilotKekMasterVersion2Key32B");
    }

    private void initializeKek(int version, String seed) {
        byte[] keyBytes = new byte[32];
        byte[] seedBytes = seed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(seedBytes, 0, keyBytes, 0, Math.min(seedBytes.length, 32));
        kekMap.put(version, new SecretKeySpec(keyBytes, "AES"));
    }

    public void resetToDefaultKeys() {
        kekMap.clear();
        initializeKek(1, "RepoPilotKekMasterVersion1Key32B");
        initializeKek(2, "RepoPilotKekMasterVersion2Key32B");
        currentKeyVersion = 1;
    }

    public void registerKeyVersion(int version, byte[] kek32Bytes) {
        if (kek32Bytes == null || kek32Bytes.length != 32) {
            throw new IllegalArgumentException("KEK must be exactly 32 bytes (256-bit)");
        }
        kekMap.put(version, new SecretKeySpec(kek32Bytes, "AES"));
        if (version > currentKeyVersion) {
            currentKeyVersion = version;
        }
    }

    @Override
    public byte[] generateDataEncryptionKey() {
        byte[] dek = new byte[DEK_SIZE_BYTES];
        secureRandom.nextBytes(dek);
        return dek;
    }

    @Override
    public byte[] wrapDataEncryptionKey(byte[] plainDek, int keyVersion) {
        if (plainDek == null || plainDek.length != DEK_SIZE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEK must be exactly 32 bytes");
        }
        SecretKey kek = getKek(keyVersion);
        try {
            byte[] nonce = new byte[GCM_NONCE_SIZE_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, kek, spec);
            byte[] encrypted = cipher.doFinal(plainDek);

            byte[] wrapped = new byte[GCM_NONCE_SIZE_BYTES + encrypted.length];
            System.arraycopy(nonce, 0, wrapped, 0, GCM_NONCE_SIZE_BYTES);
            System.arraycopy(encrypted, 0, wrapped, GCM_NONCE_SIZE_BYTES, encrypted.length);
            return wrapped;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to wrap DEK");
        }
    }

    @Override
    public byte[] unwrapDataEncryptionKey(byte[] encryptedDek, int keyVersion) {
        if (encryptedDek == null || encryptedDek.length <= GCM_NONCE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid or corrupted wrapped DEK");
        }
        SecretKey kek = getKek(keyVersion);
        try {
            byte[] nonce = new byte[GCM_NONCE_SIZE_BYTES];
            System.arraycopy(encryptedDek, 0, nonce, 0, GCM_NONCE_SIZE_BYTES);

            int cipherLength = encryptedDek.length - GCM_NONCE_SIZE_BYTES;
            byte[] cipherBytes = new byte[cipherLength];
            System.arraycopy(encryptedDek, GCM_NONCE_SIZE_BYTES, cipherBytes, 0, cipherLength);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.DECRYPT_MODE, kek, spec);
            return cipher.doFinal(cipherBytes);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEK unwrap failed or key mismatch");
        }
    }

    @Override
    public byte[] rewrapDataEncryptionKey(byte[] encryptedDek, int oldKeyVersion, int newKeyVersion) {
        byte[] plainDek = unwrapDataEncryptionKey(encryptedDek, oldKeyVersion);
        try {
            return wrapDataEncryptionKey(plainDek, newKeyVersion);
        } finally {
            // Overwrite plainDek array in memory
            java.util.Arrays.fill(plainDek, (byte) 0);
        }
    }

    @Override
    public int getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    private SecretKey getKek(int version) {
        SecretKey kek = kekMap.get(version);
        if (kek == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown or unsupported KEK version: " + version);
        }
        return kek;
    }
}
