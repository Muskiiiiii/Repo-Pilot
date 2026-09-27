package com.repopilot.llm.crypto;

import com.repopilot.llm.dto.AuditExportEncryptionResult;

import java.time.Instant;
import java.util.UUID;

public interface AuditExportCryptoService {
    AuditExportEncryptionResult encrypt(byte[] plainGzipBytes, UUID orgId, UUID jobId, Instant snapshotTimestamp);

    byte[] decrypt(byte[] ciphertextBytes, byte[] encryptedDek, byte[] nonceBytes, int keyVersion,
                   String expectedPlaintextHash, String expectedCiphertextHash,
                   UUID orgId, UUID jobId, Instant snapshotTimestamp);

    String computeSha256Hex(byte[] data);

    String formatAad(UUID orgId, UUID jobId, Instant snapshotTimestamp);
}
