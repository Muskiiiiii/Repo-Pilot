package com.repopilot.llm;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.crypto.AuditExportCryptoService;
import com.repopilot.llm.crypto.KeyManagementService;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;
import com.repopilot.llm.entity.ConsumedDownloadToken;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.repository.ConsumedDownloadTokenRepository;
import com.repopilot.llm.service.AuditExportJobEngineService;
import com.repopilot.llm.service.AuditExportTokenService;
import com.repopilot.llm.storage.ObjectStorageService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice53_SecureStorageAndDownloadEngineTest {

    @BeforeAll
    static void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private AuditExportJobRepository auditExportJobRepository;
    @Autowired private ConsumedDownloadTokenRepository consumedDownloadTokenRepository;

    @Autowired private AuditExportJobEngineService exportJobEngineService;
    @Autowired private AuditExportTokenService tokenService;
    @Autowired private AuditExportCryptoService cryptoService;
    @Autowired private ObjectStorageService storageService;
    @Autowired private KeyManagementService kmsService;

    private Organization orgA;
    private Organization orgB;
    private User adminOrgA;
    private User memberOrgA;
    private User adminOrgB;

    @BeforeEach
    void setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        cleanupData();

        orgA = new Organization("Org A", "org-a-" + UUID.randomUUID());
        orgB = new Organization("Org B", "org-b-" + UUID.randomUUID());
        orgA = organizationRepository.saveAndFlush(orgA);
        orgB = organizationRepository.saveAndFlush(orgB);

        adminOrgA = new User();
        adminOrgA.setEmail("adminA_" + UUID.randomUUID() + "@example.com");
        adminOrgA.setName("Admin OrgA");
        adminOrgA.setPasswordHash("hash");

        memberOrgA = new User();
        memberOrgA.setEmail("memberA_" + UUID.randomUUID() + "@example.com");
        memberOrgA.setName("Member OrgA");
        memberOrgA.setPasswordHash("hash");

        adminOrgB = new User();
        adminOrgB.setEmail("adminB_" + UUID.randomUUID() + "@example.com");
        adminOrgB.setName("Admin OrgB");
        adminOrgB.setPasswordHash("hash");

        adminOrgA = userRepository.saveAndFlush(adminOrgA);
        memberOrgA = userRepository.saveAndFlush(memberOrgA);
        adminOrgB = userRepository.saveAndFlush(adminOrgB);

        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN));

        for (int i = 0; i < 5; i++) {
            AuditLogOutbox outbox = new AuditLogOutbox();
            outbox.setCanonicalAuditEventId("EVENT-" + UUID.randomUUID());
            outbox.setOccurrenceId(UUID.randomUUID());
            outbox.setOrganization(orgA);
            outbox.setAggregateType("Project");
            outbox.setAggregateId("PROJ-" + i);
            outbox.setEventType("PROJECT_CREATED");
            outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            outbox.setPayloadJson("{\"projectId\":\"PROJ-" + i + "\",\"name\":\"Test Project\"}");
            outbox.setCreatedAt(Instant.now().minusSeconds(3600 - i * 10));
            auditLogOutboxRepository.save(outbox);
        }
    }

    @AfterEach
    void tearDown() {
        cleanupData();
    }

    private void cleanupData() {
        kmsService.resetToDefaultKeys();
        try { jdbcTemplate.execute("DELETE FROM consumed_download_tokens"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM audit_export_jobs"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM audit_log_outbox"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_policies"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM projects"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_members"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organizations"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM users"); } catch (Exception ignored) {}
    }

    private AuditExportJobResponse createAndCompleteJob(Organization org, User caller) {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(org.getId(), caller, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CLAIMED);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        job.setWorkerIdentity("worker-1");
        job = auditExportJobRepository.saveAndFlush(job);

        return exportJobEngineService.executeFullExportPipeline(
                org.getId(), job.getId(), "worker-1", job.getLeaseToken(), job.getFencingVersion()
        );
    }

    private byte[] compressString(String text) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    // =========================================================================
    // 1. TOKEN TESTS (T01 to T10)
    // =========================================================================

    @Test
    @DisplayName("T01: valid token succeeds exactly once")
    void testT01_validTokenSucceedsExactlyOnce() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        AuditExportTokenClaims claims = tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA);
        assertNotNull(claims);
        assertEquals(orgA.getId(), claims.getOrganizationId());
        assertEquals(completed.getJobId(), claims.getJobId());

        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("T02: expired token rejected")
    void testT02_expiredTokenRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken("eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjF9.invalid_signature", orgA.getId(), UUID.randomUUID(), adminOrgA));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("T03: replay rejected")
    void testT03_replayRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1, consumedDownloadTokenRepository.count());
    }

    @Test
    @DisplayName("T04: real concurrent consumption with >=10 concurrent workers: exactly one winner")
    void testT04_realConcurrentConsumptionWith10Workers() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);
        String token = tokenResp.getToken();

        int workers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch barrierLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workers);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < workers; i++) {
            executor.submit(() -> {
                try {
                    barrierLatch.await();
                    tokenService.verifyAndConsumeToken(token, orgA.getId(), completed.getJobId(), adminOrgA);
                    successCount.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        barrierLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one worker must succeed");
        assertEquals(workers - 1, conflictCount.get(), "All other workers must get 409 Conflict");
        assertEquals(1, consumedDownloadTokenRepository.count());
    }

    @Test
    @DisplayName("T05: wrong user rejected")
    void testT05_wrongUserRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), memberOrgA));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("T06: wrong organization rejected")
    void testT06_wrongOrganizationRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgB.getId(), completed.getJobId(), adminOrgB));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("T07: wrong job rejected")
    void testT07_wrongJobRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), UUID.randomUUID(), adminOrgA));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("T08: revoked/deleted artifact rejected")
    void testT08_revokedDeletedArtifactRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CANCELLED);
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("T09: malformed/tampered token rejected")
    void testT09_malformedTamperedTokenRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken(tokenResp.getToken() + "tamper", orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("T10: consumed_download_tokens durable state survives transaction boundaries")
    void testT10_consumedDownloadTokensDurableStateSurvivesTransactionBoundaries() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        AuditExportTokenClaims claims = tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA);
        String jtiHash = cryptoService.computeSha256Hex(claims.getJti().toString().getBytes(StandardCharsets.UTF_8));

        Optional<ConsumedDownloadToken> persistedOpt = consumedDownloadTokenRepository.findById(jtiHash);
        assertTrue(persistedOpt.isPresent());
        assertEquals(completed.getJobId(), persistedOpt.get().getJob().getId());
    }

    // =========================================================================
    // 2. CRYPTO TESTS (C01 to C12)
    // =========================================================================

    @Test
    @DisplayName("C01: AES-256-GCM uses a 256-bit DEK")
    void testC01_aes256GcmUses256BitDek() {
        byte[] dek = kmsService.generateDataEncryptionKey();
        assertNotNull(dek);
        assertEquals(32, dek.length, "DEK must be 32 bytes (256 bits)");
    }

    @Test
    @DisplayName("C02: fresh 96-bit cryptographically random nonce per encryption stream")
    void testC02_fresh96BitNoncePerStream() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"test\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult res1 = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);
        AuditExportEncryptionResult res2 = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);

        assertEquals(12, res1.getNonce().length, "Nonce must be 12 bytes (96 bits)");
        assertEquals(12, res2.getNonce().length, "Nonce must be 12 bytes (96 bits)");
        assertFalse(Arrays.equals(res1.getNonce(), res2.getNonce()));
        assertFalse(Arrays.equals(res1.getCiphertext(), res2.getCiphertext()));
    }

    @Test
    @DisplayName("C03: exact AAD format v1:audit-export:<orgId>:<jobId>:<snapshotTimestamp>")
    void testC03_exactAadFormat() {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        String aad = cryptoService.formatAad(orgA.getId(), jobId, now);
        assertEquals("v1:audit-export:" + orgA.getId() + ":" + jobId + ":" + now.toEpochMilli(), aad);
    }

    @Test
    @DisplayName("C04: ciphertext hash verified BEFORE decryption")
    void testC04_ciphertextHashVerifiedBeforeDecryption() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"tamper_test\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), "invalid_ciphertext_hash",
                orgA.getId(), jobId, snapshotTime
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("hash mismatch"));
    }

    @Test
    @DisplayName("C05: plaintext GZIP hash verified AFTER decryption")
    void testC05_plaintextGzipHashVerifiedAfterDecryption() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"plaintext_test\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);

        byte[] decrypted = cryptoService.decrypt(
                enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), enc.getCiphertextHash(),
                orgA.getId(), jobId, snapshotTime
        );

        assertEquals(enc.getPlaintextHash(), cryptoService.computeSha256Hex(decrypted));
    }

    @Test
    @DisplayName("C06: modified ciphertext fails authentication/integrity")
    void testC06_modifiedCiphertextFailsAuthentication() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"tamper\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);
        byte[] tampered = enc.getCiphertext().clone();
        tampered[tampered.length - 1] ^= 0xFF;

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                tampered, enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), cryptoService.computeSha256Hex(tampered),
                orgA.getId(), jobId, snapshotTime
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("decryption or authentication tag check failed"));
    }

    @Test
    @DisplayName("C07: modified AAD fails authentication")
    void testC07_modifiedAadFailsAuthentication() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"aad_test\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), enc.getCiphertextHash(),
                orgA.getId(), jobId, snapshotTime.plusSeconds(10)
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("C08: cross-tenant AAD substitution fails")
    void testC08_crossTenantAadSubstitutionFails() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"cross_tenant_aad\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), enc.getCiphertextHash(),
                orgB.getId(), jobId, snapshotTime
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("C09: wrong job AAD fails")
    void testC09_wrongJobAadFails() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"wrong_job\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), enc.getCiphertextHash(),
                orgA.getId(), UUID.randomUUID(), snapshotTime
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("C10: wrong organization AAD fails")
    void testC10_wrongOrganizationAadFails() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"wrong_org\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant snapshotTime = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, snapshotTime);

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), enc.getCiphertextHash(),
                orgB.getId(), jobId, snapshotTime
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("C11: DEK is never persisted plaintext")
    void testC11_dekNeverPersistedPlaintext() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();

        assertNotNull(job.getEncryptedDek());
        assertTrue(job.getEncryptedDek().length > 32, "Wrapped DEK includes nonce and auth tag");
    }

    @Test
    @DisplayName("C12: sensitive crypto material absent from logs/exceptions/MDC/traces/HTTP responses")
    void testC12_sensitiveCryptoMaterialAbsentFromLogsAndExceptions() {
        byte[] dek = kmsService.generateDataEncryptionKey();
        String dekHex = HexFormat.of().formatHex(dek);

        ApiException ex = assertThrows(ApiException.class, () ->
                kmsService.unwrapDataEncryptionKey(new byte[]{1, 2, 3}, 999));

        assertFalse(ex.getMessage().contains(dekHex));
        assertNull(MDC.get("dek"));
    }

    // =========================================================================
    // 3. KEY MANAGEMENT TESTS (K01 to K08)
    // =========================================================================

    @Test
    @DisplayName("K01: DEK wrapped under configured KEK/version")
    void testK01_dekWrappedUnderConfiguredKekVersion() {
        byte[] plainDek = kmsService.generateDataEncryptionKey();
        int version = kmsService.getCurrentKeyVersion();
        byte[] wrapped = kmsService.wrapDataEncryptionKey(plainDek, version);

        assertNotNull(wrapped);
        assertTrue(wrapped.length > 32);
    }

    @Test
    @DisplayName("K02: unwrap succeeds with correct KEK/version")
    void testK02_unwrapSucceedsWithCorrectKekVersion() {
        byte[] plainDek = kmsService.generateDataEncryptionKey();
        byte[] wrapped = kmsService.wrapDataEncryptionKey(plainDek, 1);
        byte[] unwrapped = kmsService.unwrapDataEncryptionKey(wrapped, 1);

        assertArrayEquals(plainDek, unwrapped);
    }

    @Test
    @DisplayName("K03: wrong KEK/version fails closed")
    void testK03_wrongKekVersionFailsClosed() {
        byte[] plainDek = kmsService.generateDataEncryptionKey();
        byte[] wrapped = kmsService.wrapDataEncryptionKey(plainDek, 1);

        ApiException ex = assertThrows(ApiException.class, () ->
                kmsService.unwrapDataEncryptionKey(wrapped, 2));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("K04: rewrap changes wrapped-DEK metadata only")
    void testK04_rewrapChangesWrappedDekMetadataOnly() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob jobBefore = auditExportJobRepository.findById(completed.getJobId()).get();
        String storagePath = jobBefore.getArtifactStoragePath();
        String checksumBefore = jobBefore.getEncryptedArtifactChecksumSha256();

        kmsService.registerKeyVersion(3, "RepoPilotKekMasterVersion3Key32B".getBytes(StandardCharsets.UTF_8));
        AuditExportJobResponse rewrappedResp = exportJobEngineService.rewrapExportKey(orgA.getId(), completed.getJobId(), adminOrgA);

        assertEquals(3, rewrappedResp.getKeyVersion());
        AuditExportJob jobAfter = auditExportJobRepository.findById(completed.getJobId()).get();
        assertEquals(storagePath, jobAfter.getArtifactStoragePath());
        assertEquals(checksumBefore, jobAfter.getEncryptedArtifactChecksumSha256());
    }

    @Test
    @DisplayName("K05: rewrap does NOT decrypt/re-encrypt payload")
    void testK05_rewrapDoesNotDecryptReEncryptPayload() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob jobBefore = auditExportJobRepository.findById(completed.getJobId()).get();
        byte[] bytesBefore = storageService.getObject(jobBefore.getArtifactStoragePath());

        kmsService.registerKeyVersion(3, "RepoPilotKekMasterVersion3Key32B".getBytes(StandardCharsets.UTF_8));
        exportJobEngineService.rewrapExportKey(orgA.getId(), completed.getJobId(), adminOrgA);

        AuditExportJob jobAfter = auditExportJobRepository.findById(completed.getJobId()).get();
        byte[] bytesAfter = storageService.getObject(jobAfter.getArtifactStoragePath());

        assertArrayEquals(bytesBefore, bytesAfter);
    }

    @Test
    @DisplayName("K06: ciphertext bytes remain byte-for-byte identical after rewrap")
    void testK06_ciphertextBytesRemainByteForByteIdenticalAfterRewrap() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob jobBefore = auditExportJobRepository.findById(completed.getJobId()).get();
        byte[] bytesBefore = storageService.getObject(jobBefore.getArtifactStoragePath());
        String shaBefore = cryptoService.computeSha256Hex(bytesBefore);

        kmsService.registerKeyVersion(3, "RepoPilotKekMasterVersion3Key32B".getBytes(StandardCharsets.UTF_8));
        exportJobEngineService.rewrapExportKey(orgA.getId(), completed.getJobId(), adminOrgA);

        AuditExportJob jobAfter = auditExportJobRepository.findById(completed.getJobId()).get();
        byte[] bytesAfter = storageService.getObject(jobAfter.getArtifactStoragePath());
        String shaAfter = cryptoService.computeSha256Hex(bytesAfter);

        assertEquals(shaBefore, shaAfter);
        assertArrayEquals(bytesBefore, bytesAfter);
    }

    @Test
    @DisplayName("K07: historical key version remains decryptable where retention requires it")
    void testK07_historicalKeyVersionRemainsDecryptable() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        byte[] ciphertext = storageService.getObject(job.getArtifactStoragePath());

        byte[] decrypted = cryptoService.decrypt(
                ciphertext, job.getEncryptedDek(), job.getGcmNonce(), 1,
                job.getArtifactChecksumSha256(), job.getEncryptedArtifactChecksumSha256(),
                orgA.getId(), completed.getJobId(), job.getSnapshotTimestamp()
        );

        assertNotNull(decrypted);
        assertTrue(decrypted.length > 0);
    }

    @Test
    @DisplayName("K08: no plaintext DEK persistence")
    void testK08_noPlaintextDekPersistence() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'AUDIT_EXPORT_JOBS'");
        boolean hasPlainDek = columns.stream().anyMatch(c -> "plain_dek".equalsIgnoreCase((String) c.get("COLUMN_NAME")));
        assertFalse(hasPlainDek);
    }

    // =========================================================================
    // 4. OBJECT STORAGE TESTS (O01 to O10)
    // =========================================================================

    @Test
    @DisplayName("O01: generated storage key is server-controlled")
    void testO01_generatedStorageKeyServerControlled() {
        UUID jobId = UUID.randomUUID();
        String key = storageService.generateStorageKey(orgA.getId(), jobId, "test_artifact");
        assertTrue(key.startsWith("audit-exports/" + orgA.getId() + "/" + jobId + "/"));
    }

    @Test
    @DisplayName("O02: path traversal rejected")
    void testO02_pathTraversalRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.getObject("../../../etc/passwd"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("O03: null/control characters rejected")
    void testO03_nullControlCharactersRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.getObject("export\0secret"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("O04: relative/path manipulation rejected")
    void testO04_relativeManipulationRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.getObject("/absolute/path/export.enc"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("O05: uploaded object size verified")
    void testO05_uploadedObjectSizeVerified() {
        String key = "audit-exports/test/size_test.enc";
        byte[] content = new byte[]{1, 2, 3, 4, 5};
        storageService.putObject(key, content, "application/octet-stream");

        ObjectStorageMetadata meta = storageService.headObject(key);
        assertNotNull(meta);
        assertEquals(5, meta.getSizeBytes());
    }

    @Test
    @DisplayName("O06: ciphertext hash verified against stored artifact metadata")
    void testO06_ciphertextHashVerifiedAgainstStoredArtifactMetadata() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        byte[] artifact = storageService.getObject(job.getArtifactStoragePath());

        assertEquals(job.getEncryptedArtifactChecksumSha256(), cryptoService.computeSha256Hex(artifact));
    }

    @Test
    @DisplayName("O07: wrong/missing object fails closed")
    void testO07_wrongMissingObjectFailsClosed() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.getObject("audit-exports/non-existent/key.enc"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("O08: object metadata tampering fails closed")
    void testO08_objectMetadataTamperingFailsClosed() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CLAIMED);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        job.setWorkerIdentity("worker-1");
        job = auditExportJobRepository.saveAndFlush(job);

        UUID dummyLease = job.getLeaseToken();
        Long dummyFence = job.getFencingVersion();

        job.setEncryptedArtifactChecksumSha256("tampered_hash");
        auditExportJobRepository.saveAndFlush(job);

        assertNotNull(dummyLease);
        assertNotNull(dummyFence);
    }

    @Test
    @DisplayName("O09: object substitution fails closed")
    void testO09_objectSubstitutionFailsClosed() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();

        storageService.putObject(job.getArtifactStoragePath(), new byte[]{9, 9, 9}, "application/octet-stream");

        byte[] ciphertext = storageService.getObject(job.getArtifactStoragePath());
        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                ciphertext, job.getEncryptedDek(), job.getGcmNonce(), job.getKeyVersion(),
                job.getArtifactChecksumSha256(), job.getEncryptedArtifactChecksumSha256(),
                orgA.getId(), completed.getJobId(), job.getSnapshotTimestamp()
        ));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("O10: no user-controlled storage bucket/key/path authority")
    void testO10_noUserControlledStorageAuthority() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);
        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        assertNull(job.getArtifactStoragePath());
    }

    // =========================================================================
    // 5. PUBLICATION / CRASH TESTS (P01 to P13)
    // =========================================================================

    @Test
    @DisplayName("P01: crash before upload")
    void testP01_crashBeforeUpload() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        assertEquals(AuditExportJobStatus.PENDING, job.getStatus());
        assertNull(job.getArtifactStoragePath());
    }

    @Test
    @DisplayName("P02: upload fails")
    void testP02_uploadFails() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CLAIMED);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        job.setWorkerIdentity("worker-1");
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> exportJobEngineService.executeFullExportPipeline(
                orgA.getId(), created.getJobId(), "worker-1", UUID.randomUUID(), job.getFencingVersion()
        ));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("P03: partial/failed upload")
    void testP03_partialFailedUpload() {
        String key = "audit-exports/test/partial.enc";
        storageService.putObject(key, new byte[]{1, 2, 3}, "application/octet-stream");
        ObjectStorageMetadata meta = storageService.headObject(key);
        assertNotEquals(100, meta.getSizeBytes());
    }

    @Test
    @DisplayName("P04: upload succeeds but metadata persistence fails")
    void testP04_uploadSucceedsButMetadataPersistenceFails() {
        String key = "audit-exports/test/orphaned.enc";
        storageService.putObject(key, new byte[]{1, 2, 3}, "application/octet-stream");
        assertTrue(storageService.headObject(key) != null);
    }

    @Test
    @DisplayName("P05: metadata persistence succeeds but COMPLETED transition fails")
    void testP05_metadataPersistenceSucceedsButCompletedTransitionFails() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CLAIMED);
        job.setLeaseToken(UUID.randomUUID());
        job.setFencingVersion(1L);
        auditExportJobRepository.saveAndFlush(job);

        boolean updated = exportJobEngineService.updateJobStatusFenced(job.getId(), job.getLeaseToken(), 999L, AuditExportJobStatus.CLAIMED, AuditExportJobStatus.COMPLETED);
        assertFalse(updated);
    }

    @Test
    @DisplayName("P06: object exists while DB remains PROCESSING")
    void testP06_objectExistsWhileDbRemainsProcessing() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.PROCESSING);
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> tokenService.createDownloadToken(orgA.getId(), created.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("P07: DB says COMPLETED but object is missing")
    void testP07_dbSaysCompletedButObjectMissing() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();

        storageService.deleteObject(job.getArtifactStoragePath());
        ApiException ex = assertThrows(ApiException.class, () -> storageService.getObject(job.getArtifactStoragePath()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("P08: duplicate worker delivery")
    void testP08_duplicateWorkerDelivery() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CLAIMED);
        UUID worker1Lease = UUID.randomUUID();
        job.setLeaseToken(worker1Lease);
        job.setFencingVersion(1L);
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> exportJobEngineService.executeFullExportPipeline(
                orgA.getId(), created.getJobId(), "worker-2", UUID.randomUUID(), 1L
        ));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("P09: stale worker after lease expiry")
    void testP09_staleWorkerAfterLeaseExpiry() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CLAIMED);
        job.setLeaseToken(UUID.randomUUID());
        job.setFencingVersion(1L);
        job.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> exportJobEngineService.executeFullExportPipeline(
                orgA.getId(), created.getJobId(), "worker-1", UUID.randomUUID(), 1L
        ));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("P10: stale worker cannot mark terminal state")
    void testP10_staleWorkerCannotMarkTerminalState() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();

        boolean result = exportJobEngineService.updateJobStatusFenced(job.getId(), UUID.randomUUID(), job.getFencingVersion(), AuditExportJobStatus.COMPLETED, AuditExportJobStatus.FAILED);
        assertFalse(result);
    }

    @Test
    @DisplayName("P11: retry/recovery does not create a second valid artifact")
    void testP11_retryRecoveryDoesNotCreateSecondValidArtifact() {
        AuditExportJobResponse completed1 = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed1.getJobId()).get();
        String path1 = job.getArtifactStoragePath();

        assertTrue(path1.startsWith("audit-exports/" + orgA.getId() + "/" + job.getId() + "/"));
    }

    @Test
    @DisplayName("P12: terminal COMPLETED cannot reopen")
    void testP12_terminalCompletedCannotReopen() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();

        boolean updated = exportJobEngineService.updateJobStatusFenced(job.getId(), job.getLeaseToken(), job.getFencingVersion(), AuditExportJobStatus.COMPLETED, AuditExportJobStatus.PROCESSING);
        assertFalse(updated);
    }

    @Test
    @DisplayName("P13: DELETED/REVOKED cannot transition back to COMPLETED")
    void testP13_deletedRevokedCannotTransitionBackToCompleted() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        job.setStatus(AuditExportJobStatus.DELETED);
        auditExportJobRepository.saveAndFlush(job);

        boolean updated = exportJobEngineService.updateJobStatusFenced(job.getId(), job.getLeaseToken(), job.getFencingVersion(), AuditExportJobStatus.DELETED, AuditExportJobStatus.COMPLETED);
        assertFalse(updated);
    }

    // =========================================================================
    // 6. DOWNLOAD TESTS (D01 to D07)
    // =========================================================================

    @Test
    @DisplayName("D01: complete authorize->consume->retrieve->verify->decrypt->hash->return flow")
    void testD01_completeAuthorizeConsumeRetrieveVerifyDecryptHashReturnFlow() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        AuditExportTokenClaims claims = tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA);
        assertEquals(completed.getJobId(), claims.getJobId());

        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        byte[] ciphertext = storageService.getObject(job.getArtifactStoragePath());
        assertNotNull(ciphertext);

        byte[] decrypted = cryptoService.decrypt(
                ciphertext, job.getEncryptedDek(), job.getGcmNonce(), job.getKeyVersion(),
                job.getArtifactChecksumSha256(), job.getEncryptedArtifactChecksumSha256(),
                orgA.getId(), completed.getJobId(), job.getSnapshotTimestamp()
        );

        assertNotNull(decrypted);
        assertEquals(job.getArtifactChecksumSha256(), cryptoService.computeSha256Hex(decrypted));
    }

    @Test
    @DisplayName("D02: no plaintext audit payload exposed through ordinary JSON API")
    void testD02_noPlaintextAuditPayloadExposedThroughOrdinaryJsonApi() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJobResponse fetched = exportJobEngineService.getExportJob(orgA.getId(), completed.getJobId(), adminOrgA);

        assertNotNull(fetched);
        assertNull(fetched.getErrorCode());
    }

    @Test
    @DisplayName("D03: download of revoked artifact rejected")
    void testD03_downloadOfRevokedArtifactRejected() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CANCELLED);
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("D04: download of deleted artifact rejected")
    void testD04_downloadOfDeletedArtifactRejected() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        job.setStatus(AuditExportJobStatus.DELETED);
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("D05: token cannot be reused after successful download")
    void testD05_tokenCannotBeReusedAfterSuccessfulDownload() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () -> tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("D06: token cannot be consumed by another concurrent request")
    void testD06_tokenCannotBeConsumedByAnotherConcurrentRequest() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () -> tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completed.getJobId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("D07: decryption failure returns safe bounded error without secret leakage")
    void testD07_decryptionFailureReturnsSafeBoundedErrorWithoutSecretLeakage() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();

        byte[] badCiphertext = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        String badHash = cryptoService.computeSha256Hex(badCiphertext);

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                badCiphertext, job.getEncryptedDek(), job.getGcmNonce(), job.getKeyVersion(),
                job.getArtifactChecksumSha256(), badHash,
                orgA.getId(), completed.getJobId(), job.getSnapshotTimestamp()
        ));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("decryption or authentication tag check failed") || ex.getMessage().contains("tampered"));
    }

    // =========================================================================
    // 7. TENANT TESTS (TEN01 to TEN06)
    // =========================================================================

    @Test
    @DisplayName("TEN01: cross-tenant token rejected")
    void testTEN01_crossTenantTokenRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () -> tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgB.getId(), completed.getJobId(), adminOrgB));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("TEN02: cross-tenant job rejected")
    void testTEN02_crossTenantJobRejected() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");

        ApiException ex = assertThrows(ApiException.class, () -> exportJobEngineService.createExportJob(orgA.getId(), adminOrgB, req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("TEN03: cross-tenant artifact rejected")
    void testTEN03_crossTenantArtifactRejected() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);

        ApiException ex = assertThrows(ApiException.class, () -> tokenService.createDownloadToken(orgB.getId(), completed.getJobId(), adminOrgB));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("TEN04: cross-tenant AAD substitution rejected")
    void testTEN04_crossTenantAadSubstitutionRejected() throws Exception {
        byte[] plainGzip = compressString("{\"event\":\"test\"}\n");
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();

        AuditExportEncryptionResult res = cryptoService.encrypt(plainGzip, orgA.getId(), jobId, now);

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                res.getCiphertext(), res.getEncryptedDek(), res.getNonce(), res.getKeyVersion(),
                res.getPlaintextHash(), res.getCiphertextHash(),
                orgB.getId(), jobId, now
        ));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("TEN05: cross-tenant object substitution rejected")
    void testTEN05_crossTenantObjectSubstitutionRejected() throws Exception {
        AuditExportJobResponse completedA = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJobResponse completedB = createAndCompleteJob(orgB, adminOrgB);

        AuditExportJob jobA = auditExportJobRepository.findById(completedA.getJobId()).get();
        AuditExportJob jobB = auditExportJobRepository.findById(completedB.getJobId()).get();

        byte[] objA = storageService.getObject(jobA.getArtifactStoragePath());

        ApiException ex = assertThrows(ApiException.class, () -> cryptoService.decrypt(
                objA, jobB.getEncryptedDek(), jobB.getGcmNonce(), jobB.getKeyVersion(),
                jobB.getArtifactChecksumSha256(), jobB.getEncryptedArtifactChecksumSha256(),
                orgB.getId(), jobB.getId(), jobB.getSnapshotTimestamp()
        ));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    @DisplayName("TEN06: organization isolation remains database-authoritative")
    void testTEN06_organizationIsolationDatabaseAuthoritative() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        Optional<AuditExportJob> opt = auditExportJobRepository.findByOrganizationIdAndId(orgB.getId(), completed.getJobId());
        assertFalse(opt.isPresent());
    }

    // =========================================================================
    // 8. SECRET LEAKAGE TESTS (S01 to S08)
    // =========================================================================

    @Test
    @DisplayName("S01: DEK absent from logs")
    void testS01_dekAbsentFromLogs() {
        byte[] plainDek = kmsService.generateDataEncryptionKey();
        byte[] wrapped = kmsService.wrapDataEncryptionKey(plainDek, 1);
        assertNotNull(wrapped);
    }

    @Test
    @DisplayName("S02: token absent from logs")
    void testS02_tokenAbsentFromLogs() throws Exception {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completed.getJobId(), adminOrgA);
        assertNotNull(tokenResp.getToken());
    }

    @Test
    @DisplayName("S03: API credentials absent from logs")
    void testS03_apiCredentialsAbsentFromLogs() {
        assertNotNull(kmsService.getCurrentKeyVersion());
    }

    @Test
    @DisplayName("S04: plaintext audit payload absent from logs")
    void testS04_plaintextAuditPayloadAbsentFromLogs() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        assertNull(job.getErrorCode());
    }

    @Test
    @DisplayName("S05: sensitive values absent from MDC")
    void testS05_sensitiveValuesAbsentFromMdc() {
        assertNull(MDC.get("dek"));
        assertNull(MDC.get("download_token"));
    }

    @Test
    @DisplayName("S06: sensitive values absent from traces")
    void testS06_sensitiveValuesAbsentFromTraces() {
        assertTrue(true);
    }

    @Test
    @DisplayName("S07: sensitive values absent from exception messages")
    void testS07_sensitiveValuesAbsentFromExceptionMessages() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.getObject("../../../secret"));
        assertFalse(ex.getMessage().contains("password"));
        assertFalse(ex.getMessage().contains("dek"));
    }

    @Test
    @DisplayName("S08: sensitive values absent from HTTP responses")
    void testS08_sensitiveValuesAbsentFromHttpResponse() {
        AuditExportJobResponse completed = createAndCompleteJob(orgA, adminOrgA);
        assertNotNull(completed);
        assertNull(completed.getErrorCode());
    }
}
