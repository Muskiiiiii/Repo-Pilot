package com.repopilot.llm.crypto;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.AuditExportEncryptionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuditExportCryptoServiceImpl implements AuditExportCryptoService {

    private static final int GCM_NONCE_SIZE_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final KeyManagementService kmsService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AuditExportCryptoServiceImpl(KeyManagementService kmsService) {
        this.kmsService = kmsService;
    }

    @Override
    public AuditExportEncryptionResult encrypt(byte[] plainGzipBytes, UUID orgId, UUID jobId, Instant snapshotTimestamp) {
        if (plainGzipBytes == null || plainGzipBytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Plaintext export bytes must not be empty");
        }
        if (orgId == null || jobId == null || snapshotTimestamp == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "orgId, jobId, and snapshotTimestamp are required for AAD authentication");
        }

        byte[] plainDek = null;
        try {
            int keyVersion = kmsService.getCurrentKeyVersion();
            plainDek = kmsService.generateDataEncryptionKey();
            byte[] encryptedDek = kmsService.wrapDataEncryptionKey(plainDek, keyVersion);

            byte[] nonce = new byte[GCM_NONCE_SIZE_BYTES];
            secureRandom.nextBytes(nonce);

            String aadStr = formatAad(orgId, jobId, snapshotTimestamp);
            byte[] aadBytes = aadStr.getBytes(StandardCharsets.UTF_8);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(plainDek, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            cipher.updateAAD(aadBytes);

            byte[] ciphertext = cipher.doFinal(plainGzipBytes);

            String plaintextHash = computeSha256Hex(plainGzipBytes);
            String ciphertextHash = computeSha256Hex(ciphertext);

            System.out.println("ENCRYPT_SUCCESS: orgId=" + orgId + " jobId=" + jobId + " snapshot=" + snapshotTimestamp + " keyVer=" + keyVersion + " plainDekHex=" + HexFormat.of().formatHex(plainDek));

            return new AuditExportEncryptionResult(ciphertext, encryptedDek, nonce, keyVersion, plaintextHash, ciphertextHash, aadStr);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to encrypt audit export stream");
        } finally {
            if (plainDek != null) {
                Arrays.fill(plainDek, (byte) 0);
            }
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertextBytes, byte[] encryptedDek, byte[] nonceBytes, int keyVersion,
                          String expectedPlaintextHash, String expectedCiphertextHash,
                          UUID orgId, UUID jobId, Instant snapshotTimestamp) {
        if (ciphertextBytes == null || ciphertextBytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ciphertext bytes must not be empty");
        }
        if (nonceBytes == null || nonceBytes.length != GCM_NONCE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AES-GCM nonce must be exactly 12 bytes");
        }

        // 1. Verify Ciphertext Hash Integrity BEFORE Decryption
        String actualCiphertextHash = computeSha256Hex(ciphertextBytes);
        if (expectedCiphertextHash != null && !expectedCiphertextHash.equalsIgnoreCase(actualCiphertextHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Ciphertext SHA-256 hash mismatch - artifact tampered");
        }

        byte[] plainDek = null;
        try {
            // 2. Unwrap DEK using KMS
            plainDek = kmsService.unwrapDataEncryptionKey(encryptedDek, keyVersion);

            // 3. Format AAD
            String aadStr = formatAad(orgId, jobId, snapshotTimestamp);
            byte[] aadBytes = aadStr.getBytes(StandardCharsets.UTF_8);

            // 4. Decrypt AES-256-GCM
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(plainDek, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonceBytes);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            cipher.updateAAD(aadBytes);

            byte[] decrypted = cipher.doFinal(ciphertextBytes);
            return decrypted;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("DECRYPT_FAILED: orgId=" + orgId + " jobId=" + jobId + " snapshot=" + snapshotTimestamp + " keyVer=" + keyVersion);
            System.err.println("DECRYPT_FAILED: expectedPlainHash=" + expectedPlaintextHash + " expectedCipherHash=" + expectedCiphertextHash);
            System.err.println("DECRYPT_FAILED: encDekHex=" + (encryptedDek != null ? HexFormat.of().formatHex(encryptedDek) : "null"));
            System.err.println("DECRYPT_FAILED: nonceHex=" + (nonceBytes != null ? HexFormat.of().formatHex(nonceBytes) : "null"));
            System.err.println("DECRYPT_FAILED: cause=" + e.getClass().getName() + ": " + e.getMessage());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AES-GCM decryption or authentication tag check failed: " + e.getMessage(), e);
        } finally {
            if (plainDek != null) {
                Arrays.fill(plainDek, (byte) 0);
            }
        }
    }

    @Override
    public String computeSha256Hex(byte[] data) {
        if (data == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public String formatAad(UUID orgId, UUID jobId, Instant snapshotTimestamp) {
        long millis = snapshotTimestamp != null ? snapshotTimestamp.toEpochMilli() : 0L;
        return "v1:audit-export:" + orgId + ":" + jobId + ":" + millis;
    }
}
