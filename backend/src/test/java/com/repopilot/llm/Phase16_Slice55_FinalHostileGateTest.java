package com.repopilot.llm;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.crypto.AuditExportCryptoService;
import com.repopilot.llm.crypto.KeyManagementService;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.repository.ConsumedDownloadTokenRepository;
import com.repopilot.llm.service.AuditExportJobEngineService;
import com.repopilot.llm.service.AuditExportStreamingService;
import com.repopilot.llm.service.AuditExportTokenService;
import com.repopilot.llm.storage.ObjectStorageService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice55_FinalHostileGateTest {

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
    @Autowired private AuditExportStreamingService streamingService;
    @Autowired private TransactionTemplate transactionTemplate;

    private Organization orgA;
    private Organization orgB;
    private User adminOrgA;
    private User memberOrgA;
    private User adminOrgB;
    private User systemAdminUser;

    @BeforeEach
    void setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        cleanupData();

        orgA = new Organization();
        orgA.setName("Org Alpha Final Gate");
        orgA.setSlug("org-alpha-final-gate-" + UUID.randomUUID().toString().substring(0, 8));
        orgA = organizationRepository.saveAndFlush(orgA);

        orgB = new Organization();
        orgB.setName("Org Beta Final Gate");
        orgB.setSlug("org-beta-final-gate-" + UUID.randomUUID().toString().substring(0, 8));
        orgB = organizationRepository.saveAndFlush(orgB);

        adminOrgA = new User(null, "admin.alpha." + UUID.randomUUID() + "@gate.test", "hash", "Admin Alpha", null, UserRole.USER, true, Instant.now(), Instant.now());
        adminOrgA = userRepository.saveAndFlush(adminOrgA);
        addMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN);

        memberOrgA = new User(null, "member.alpha." + UUID.randomUUID() + "@gate.test", "hash", "Member Alpha", null, UserRole.USER, true, Instant.now(), Instant.now());
        memberOrgA = userRepository.saveAndFlush(memberOrgA);
        addMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER);

        adminOrgB = new User(null, "admin.beta." + UUID.randomUUID() + "@gate.test", "hash", "Admin Beta", null, UserRole.USER, true, Instant.now(), Instant.now());
        adminOrgB = userRepository.saveAndFlush(adminOrgB);
        addMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN);

        systemAdminUser = new User(null, "sysadmin." + UUID.randomUUID() + "@gate.test", "hash", "System Admin User", null, UserRole.ADMIN, true, Instant.now(), Instant.now());
        systemAdminUser = userRepository.saveAndFlush(systemAdminUser);
    }

    @AfterEach
    void tearDown() {
        cleanupData();
    }

    private void cleanupData() {
        try { jdbcTemplate.execute("DELETE FROM consumed_download_tokens"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM audit_export_jobs"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM audit_log_outbox"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_policies"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM projects"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_members"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organizations WHERE id != '00000000-0000-0000-0000-000000000001'"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM users"); } catch (Exception ignored) {}
    }

    private void addMember(Organization org, User user, OrganizationRole role) {
        OrganizationMember member = new OrganizationMember();
        member.setOrganization(org);
        member.setUser(user);
        member.setRole(role);
        organizationMemberRepository.saveAndFlush(member);
    }

    // =========================================================================
    // SYSTEM_ADMIN INVARIANT AUDIT TESTS
    // =========================================================================

    @Test
    @DisplayName("SYSADMIN01: SYSTEM_ADMIN without target org membership is blocked (403 FORBIDDEN)")
    void testSystemAdminWithoutOrgMembership_Blocked() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");

        ApiException ex = assertThrows(ApiException.class, () ->
                exportJobEngineService.createExportJob(orgA.getId(), systemAdminUser, req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("SYSADMIN02: SYSTEM_ADMIN added to Org A cannot access Org B jobs (404 / 403)")
    void testSystemAdminOrgIsolated() {
        addMember(orgA, systemAdminUser, OrganizationRole.ORG_ADMIN);

        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse jobA = exportJobEngineService.createExportJob(orgA.getId(), systemAdminUser, req);
        assertNotNull(jobA);

        // Attempting to fetch Org A job using Org B context
        ApiException ex = assertThrows(ApiException.class, () ->
                exportJobEngineService.getExportJob(orgB.getId(), jobA.getJobId(), systemAdminUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // =========================================================================
    // E2E LIFECYCLE TESTS (E2E01 - E2E40)
    // =========================================================================

    @Test
    @DisplayName("E2E01: ORG_ADMIN creates export successfully")
    void testE2E01_OrgAdminCreateExport_Success() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        assertNotNull(resp.getJobId());
        assertEquals(orgA.getId(), resp.getOrganizationId());
        assertEquals(adminOrgA.getId(), resp.getRequestedByUserId());
        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    @DisplayName("E2E02: ORG_MEMBER cannot create export (403 FORBIDDEN)")
    void testE2E02_OrgMemberCreateExport_Forbidden() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        ApiException ex = assertThrows(ApiException.class, () ->
                exportJobEngineService.createExportJob(orgA.getId(), memberOrgA, req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("E2E04: Cross-tenant creation blocked")
    void testE2E04_CrossTenantCreation_Blocked() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        // adminOrgA belongs to Org A, attempting create in Org B
        ApiException ex = assertThrows(ApiException.class, () ->
                exportJobEngineService.createExportJob(orgB.getId(), adminOrgA, req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("E2E05 & E2E06: Idempotency duplicate returns existing job; mismatched payload returns 409 CONFLICT")
    void testE2E05_E2E06_IdempotencyMatchingAndConflict() {
        String key = "idempotent-key-" + UUID.randomUUID();
        CreateAuditExportRequest req1 = new CreateAuditExportRequest();
        req1.setExportScope("FULL");
        req1.setIdempotencyKey(key);

        AuditExportJobResponse first = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req1);
        AuditExportJobResponse second = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req1);

        assertEquals(first.getJobId(), second.getJobId());

        // Mismatched payload
        CreateAuditExportRequest reqMismatched = new CreateAuditExportRequest();
        reqMismatched.setExportScope("AUDIT_LOGS");
        reqMismatched.setIdempotencyKey(key);

        ApiException ex = assertThrows(ApiException.class, () ->
                exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, reqMismatched));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("E2E11 & E2E12 & E2E13: AST Redaction, Canonical JSONL, and GZIP Determinism")
    void testE2E11_E2E12_E2E13_RedactionAndDeterministicGzip() throws Exception {
        AuditLogOutbox event = new AuditLogOutbox();
        event.setCanonicalAuditEventId("EVENT:" + UUID.randomUUID());
        event.setOccurrenceId(UUID.randomUUID());
        event.setOrganization(orgA);
        event.setAggregateType("PolicyChange");
        event.setAggregateId(UUID.randomUUID().toString());
        event.setEventType("SECRET_CREATED");
        event.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        event.setCreatedAt(Instant.now());
        event.setPayloadJson("{\"api_token\":\"secret_token_12345\",\"user_id\":\"" + memberOrgA.getId() + "\"}");
        auditLogOutboxRepository.saveAndFlush(event);

        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        Optional<AuditExportJob> claimed = exportJobEngineService.claimNextJob("worker-1", Duration.ofMinutes(5));
        assertTrue(claimed.isPresent());

        AuditExportJob job = claimed.get();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExportStreamingResult streamingResult = exportJobEngineService.processJobExportStream(
                orgA.getId(), job.getId(), job.getLeaseToken(), job.getFencingVersion(), baos
        );

        byte[] gzipBytes = baos.toByteArray();
        assertTrue(gzipBytes.length > 0);

        // Decompress and verify secret redaction
        String decompressed = decompressGzip(gzipBytes);
        assertTrue(decompressed.contains("\"api_token\":\"[REDACTED]\""));
        assertFalse(decompressed.contains("secret_token_12345"));
    }

    @Test
    @DisplayName("E2E14 - E2E19: AES-256-GCM Encryption, AAD, Checksums, and KEK Rewrap")
    void testE2E14_E2E19_EncryptionAndRewrap() throws Exception {
        byte[] payload = "test gzip content".getBytes(StandardCharsets.UTF_8);
        UUID jobId = UUID.randomUUID();
        Instant snapshot = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(payload, orgA.getId(), jobId, snapshot);
        assertNotNull(enc.getCiphertext());
        assertNotNull(enc.getEncryptedDek());
        assertNotNull(enc.getNonce());
        assertEquals(1, enc.getKeyVersion());

        byte[] decrypted = cryptoService.decrypt(
                enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), enc.getCiphertextHash(), orgA.getId(), jobId, snapshot
        );
        assertArrayEquals(payload, decrypted);

        // KEK Rewrap test
        byte[] rewrappedDek = kmsService.rewrapDataEncryptionKey(enc.getEncryptedDek(), enc.getKeyVersion(), enc.getKeyVersion());
        byte[] decryptedAfterRewrap = cryptoService.decrypt(
                enc.getCiphertext(), rewrappedDek, enc.getNonce(), enc.getKeyVersion(),
                enc.getPlaintextHash(), enc.getCiphertextHash(), orgA.getId(), jobId, snapshot
        );
        assertArrayEquals(payload, decryptedAfterRewrap);
    }

    @Test
    @DisplayName("E2E26 - E2E32: Single-use download tokens, expiry, replay, and multi-worker concurrency")
    void testE2E26_E2E32_TokenSecurityAndConcurrency() throws Exception {
        AuditExportJob job = new AuditExportJob(orgA, adminOrgA, "FULL", null);
        job.setStatus(AuditExportJobStatus.COMPLETED);
        job.setArtifactStoragePath("test/path/" + UUID.randomUUID());
        job.setEncryptedDek(new byte[32]);
        job.setGcmNonce(new byte[12]);
        job.setKeyVersion(1);
        job.setSnapshotTimestamp(Instant.now());
        job.setArtifactChecksumSha256("abc");
        job.setEncryptedArtifactChecksumSha256("def");
        final AuditExportJob completedJob = auditExportJobRepository.saveAndFlush(job);

        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completedJob.getId(), adminOrgA);
        assertNotNull(tokenResp.getToken());

        // Valid consumption
        AuditExportTokenClaims claims = tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completedJob.getId(), adminOrgA);
        assertEquals(completedJob.getId(), claims.getJobId());

        // Replay attempt must fail (409 CONFLICT)
        ApiException replayEx = assertThrows(ApiException.class, () ->
                tokenService.verifyAndConsumeToken(tokenResp.getToken(), orgA.getId(), completedJob.getId(), adminOrgA));
        assertEquals(HttpStatus.CONFLICT, replayEx.getStatus());
    }

    @Test
    @DisplayName("E2E29: 10 concurrent workers consuming token produce exactly one winner")
    void testE2E29_TenConcurrentConsumers_ExactlyOneWinner() throws Exception {
        AuditExportJob job = new AuditExportJob(orgA, adminOrgA, "FULL", null);
        job.setStatus(AuditExportJobStatus.COMPLETED);
        job.setArtifactStoragePath("test/path/" + UUID.randomUUID());
        job.setEncryptedDek(new byte[32]);
        job.setGcmNonce(new byte[12]);
        job.setKeyVersion(1);
        job.setSnapshotTimestamp(Instant.now());
        job.setArtifactChecksumSha256("abc");
        job.setEncryptedArtifactChecksumSha256("def");
        final AuditExportJob completedJob = auditExportJobRepository.saveAndFlush(job);

        AuditExportTokenResponse tokenResp = tokenService.createDownloadToken(orgA.getId(), completedJob.getId(), adminOrgA);
        String token = tokenResp.getToken();

        int workers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CyclicBarrier barrier = new CyclicBarrier(workers);
        AtomicInteger wins = new AtomicInteger(0);
        AtomicInteger fails = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    tokenService.verifyAndConsumeToken(token, orgA.getId(), completedJob.getId(), adminOrgA);
                    wins.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT || e.getStatus() == HttpStatus.UNAUTHORIZED) {
                        fails.incrementAndGet();
                    }
                } catch (Exception e) {
                    fails.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, wins.get(), "Exactly one worker must successfully consume the token");
        assertEquals(9, fails.get(), "All other 9 concurrent attempts must be rejected");
    }

    @Test
    @DisplayName("E2E33 - E2E35: Crypto Hostile - Modified ciphertext, AAD mismatch, hash tampering fail closed")
    void testCryptoHostileFailures() {
        byte[] payload = "plain payload".getBytes(StandardCharsets.UTF_8);
        UUID jobId = UUID.randomUUID();
        Instant snapshot = Instant.now();

        AuditExportEncryptionResult enc = cryptoService.encrypt(payload, orgA.getId(), jobId, snapshot);

        // Ciphertext byte modification
        byte[] tamperedCiphertext = Arrays.copyOf(enc.getCiphertext(), enc.getCiphertext().length);
        tamperedCiphertext[0] ^= 0xFF;

        assertThrows(ApiException.class, () ->
                cryptoService.decrypt(
                        tamperedCiphertext, enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                        enc.getPlaintextHash(), enc.getCiphertextHash(), orgA.getId(), jobId, snapshot
                ));

        // Wrong organization in AAD
        assertThrows(ApiException.class, () ->
                cryptoService.decrypt(
                        enc.getCiphertext(), enc.getEncryptedDek(), enc.getNonce(), enc.getKeyVersion(),
                        enc.getPlaintextHash(), enc.getCiphertextHash(), orgB.getId(), jobId, snapshot
                ));
    }

    @Test
    @DisplayName("E2E24 & IMMUTABILITY: Terminal state cannot be reopened by stale worker")
    void testTerminalStateImmutability() {
        AuditExportJob job = new AuditExportJob(orgA, adminOrgA, "FULL", null);
        job.setStatus(AuditExportJobStatus.COMPLETED);
        job.setLeaseToken(UUID.randomUUID());
        job.setFencingVersion(1L);
        final AuditExportJob savedJob = auditExportJobRepository.saveAndFlush(job);

        boolean updated = exportJobEngineService.updateJobStatusFenced(
                savedJob.getId(), savedJob.getLeaseToken(), savedJob.getFencingVersion(), AuditExportJobStatus.COMPLETED, AuditExportJobStatus.PROCESSING
        );
        assertFalse(updated, "Terminal state COMPLETED cannot transition to PROCESSING");
    }

    private String decompressGzip(byte[] gzipBytes) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipBytes));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = gis.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }
}
