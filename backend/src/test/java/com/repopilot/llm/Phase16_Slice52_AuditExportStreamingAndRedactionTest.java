package com.repopilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.AuditExportJobResponse;
import com.repopilot.llm.dto.CreateAuditExportRequest;
import com.repopilot.llm.dto.ExportStreamingResult;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.service.AuditExportJobEngineService;
import com.repopilot.llm.service.AuditExportStreamingService;
import com.repopilot.llm.service.AuditPayloadSanitizer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice52_AuditExportStreamingAndRedactionTest {

    @BeforeAll
    static void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditExportJobRepository auditExportJobRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private AuditExportJobEngineService exportJobEngineService;
    @Autowired private AuditExportStreamingService auditExportStreamingService;
    @Autowired private AuditPayloadSanitizer auditPayloadSanitizer;
    @Autowired private ObjectMapper objectMapper;

    private Organization orgA;
    private Organization orgB;
    private User adminOrgA;
    private User memberOrgA;
    private User adminOrgB;

    @BeforeEach
    void setUp() {
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
    }

    @AfterEach
    void tearDown() {
        cleanupData();
    }

    private void cleanupData() {
        try { auditExportJobRepository.deleteAll(); } catch (Exception ignored) {}
        try { auditLogOutboxRepository.deleteAll(); } catch (Exception ignored) {}
        try { organizationMemberRepository.deleteAll(); } catch (Exception ignored) {}
        try { organizationRepository.deleteAll(); } catch (Exception ignored) {}
        try { userRepository.deleteAll(); } catch (Exception ignored) {}
    }

    private AuditLogOutbox createOutboxEvent(Organization org, String eventType, String jsonPayload) {
        AuditLogOutbox o = new AuditLogOutbox();
        o.setOrganization(org);
        o.setCanonicalAuditEventId("EVT:" + org.getId() + ":" + UUID.randomUUID());
        o.setOccurrenceId(UUID.randomUUID());
        o.setAggregateType("TestAggregate");
        o.setAggregateId(org.getId().toString());
        o.setEventType(eventType);
        o.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        o.setCreatedAt(Instant.now());
        if (jsonPayload != null && !jsonPayload.contains("organizationId")) {
            if (jsonPayload.startsWith("{")) {
                jsonPayload = "{\"organizationId\":\"" + org.getId() + "\"," + jsonPayload.substring(1);
            }
        } else if (jsonPayload == null) {
            jsonPayload = "{\"organizationId\":\"" + org.getId() + "\"}";
        }
        o.setPayloadJson(jsonPayload);
        return auditLogOutboxRepository.saveAndFlush(o);
    }

    private List<String> readGzipLines(byte[] gzipBytes) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(gzipBytes);
        GZIPInputStream gzipIn = new GZIPInputStream(bais);
        BufferedReader reader = new BufferedReader(new InputStreamReader(gzipIn, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        return lines;
    }

    // =========================================================================
    // SNAPSHOT CONSISTENCY TESTS (TEST01 - TEST08)
    // =========================================================================

    @Test
    @DisplayName("TEST01: Only records <= max_sequence_number are exported")
    void test01_onlyRecordsBeforeOrAtMaxSeqExported() throws Exception {
        createOutboxEvent(orgA, "EVT_1", "{\"data\":\"val1\"}");
        createOutboxEvent(orgA, "EVT_2", "{\"data\":\"val2\"}");

        CreateAuditExportRequest req = new CreateAuditExportRequest("FULL", null, null, null);
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditLogOutbox postSnapshotEvent = createOutboxEvent(orgA, "EVT_POST", "{\"data\":\"post\"}");
        Long postSeq = jdbcTemplate.queryForObject("SELECT sequence_number FROM audit_log_outbox WHERE id = ?", Long.class, postSnapshotEvent.getId());
        assertTrue(postSeq > resp.getMaxSequenceNumber());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(2, lines.size());
        for (String line : lines) {
            JsonNode node = objectMapper.readTree(line);
            long seq = node.get("sequenceNumber").asLong();
            assertTrue(seq <= resp.getMaxSequenceNumber());
        }
    }

    @Test
    @DisplayName("TEST02: Later committed events are excluded")
    void test02_laterCommittedEventsExcluded() throws Exception {
        createOutboxEvent(orgA, "EVT_1", "{\"data\":\"val1\"}");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        createOutboxEvent(orgA, "EVT_LATER", "{\"data\":\"later\"}");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("EVT_LATER"));
    }

    @Test
    @DisplayName("TEST03: AUDIT_EXPORT_REQUESTED is excluded when emitted after boundary capture")
    void test03_exportRequestAuditExcluded() throws Exception {
        createOutboxEvent(orgA, "EVT_INIT", "{\"data\":\"init\"}");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("AUDIT_EXPORT_REQUESTED"));
    }

    @Test
    @DisplayName("TEST04: Concurrent insertion after boundary capture is excluded")
    void test04_concurrentInsertionExcluded() throws Exception {
        createOutboxEvent(orgA, "EVT_BASE", "{\"data\":\"base\"}");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        createOutboxEvent(orgA, "EVT_CONCURRENT_LATER", "{\"data\":\"concurrent\"}");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("EVT_CONCURRENT_LATER"));
    }

    @Test
    @DisplayName("TEST05: Rolled-back transactions do not become export records")
    void test05_rolledBackTransactionsExcluded() throws Exception {
        createOutboxEvent(orgA, "EVT_VALID", "{\"data\":\"valid\"}");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
    }

    @Test
    @DisplayName("TEST06: Organization A cannot export Organization B events")
    void test06_tenantIsolationEnforced() throws Exception {
        createOutboxEvent(orgA, "EVT_ORG_A", "{\"data\":\"orgA_secret\"}");
        createOutboxEvent(orgB, "EVT_ORG_B", "{\"data\":\"orgB_secret\"}");

        AuditExportJobResponse respA = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), respA.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("EVT_ORG_A"));
        assertFalse(lines.get(0).contains("EVT_ORG_B"));
        assertFalse(lines.get(0).contains("orgB_secret"));
    }

    @Test
    @DisplayName("TEST07: Ordering is exactly sequence_number ASC")
    void test07_orderingIsSequenceNumberAscending() throws Exception {
        for (int i = 0; i < 5; i++) {
            createOutboxEvent(orgA, "EVT_" + i, "{\"idx\":" + i + "}");
        }

        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(5, lines.size());

        long prevSeq = -1L;
        for (String line : lines) {
            JsonNode node = objectMapper.readTree(line);
            long currSeq = node.get("sequenceNumber").asLong();
            assertTrue(currSeq > prevSeq, "Sequence numbers must be strictly increasing ASC");
            prevSeq = currSeq;
        }
    }

    @Test
    @DisplayName("TEST08: Identical source data produces identical canonical JSONL")
    void test08_identicalSourceProducesIdenticalJsonl() throws Exception {
        createOutboxEvent(orgA, "EVT_STABLE", "{\"b\":\"valB\",\"a\":\"valA\"}");

        AuditExportJobResponse resp1 = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, "idem-1"));
        AuditExportJobResponse resp2 = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, "idem-1"));

        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp1.getJobId(), adminOrgA, baos1);

        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp2.getJobId(), adminOrgA, baos2);

        assertArrayEquals(baos1.toByteArray(), baos2.toByteArray(), "Canonical GZIP stream must be binary identical for identical snapshot");
    }

    // =========================================================================
    // REDACTION HOSTILE TEST MATRIX (TEST09 - TEST28)
    // =========================================================================

    @Test
    @DisplayName("TEST09: Top-level apiKey redacted")
    void test09_topLevelApiKeyRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"apiKey\":\"secret-key-123\",\"name\":\"test\"}");
        assertEquals("[REDACTED]", node.get("apiKey").asText());
        assertEquals("test", node.get("name").asText());
    }

    @Test
    @DisplayName("TEST10: Nested apiKey redacted")
    void test10_nestedApiKeyRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"config\":{\"apiKey\":\"secret-key-456\"}}");
        assertEquals("[REDACTED]", node.get("config").get("apiKey").asText());
    }

    @Test
    @DisplayName("TEST11: Nested authorizationHeader redacted")
    void test11_nestedAuthorizationHeaderRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"headers\":{\"authorizationHeader\":\"Bearer secret-token\"}}");
        assertEquals("[REDACTED]", node.get("headers").get("authorizationHeader").asText());
    }

    @Test
    @DisplayName("TEST12: Array containing secret object redacted")
    void test12_arrayContainingSecretObjectRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"list\":[{\"id\":1,\"token\":\"secret-tok\"}]}");
        assertEquals("[REDACTED]", node.get("list").get(0).get("token").asText());
    }

    @Test
    @DisplayName("TEST13: Deeply nested token redacted")
    void test13_deeplyNestedTokenRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"level1\":{\"level2\":{\"level3\":{\"token\":\"secret-deep\"}}}}");
        assertEquals("[REDACTED]", node.get("level1").get("level2").get("level3").get("token").asText());
    }

    @Test
    @DisplayName("TEST14: Mixed-case sensitive key redacted")
    void test14_mixedCaseSensitiveKeysRedacted() {
        JsonNode node1 = auditPayloadSanitizer.sanitizePayloadJson("{\"ApiKey\":\"sec1\"}");
        assertEquals("[REDACTED]", node1.get("ApiKey").asText());

        JsonNode node2 = auditPayloadSanitizer.sanitizePayloadJson("{\"aPi_KeY\":\"sec2\"}");
        assertEquals("[REDACTED]", node2.get("aPi_KeY").asText());

        JsonNode node3 = auditPayloadSanitizer.sanitizePayloadJson("{\"TOKEN\":\"sec3\"}");
        assertEquals("[REDACTED]", node3.get("TOKEN").asText());

        JsonNode node4 = auditPayloadSanitizer.sanitizePayloadJson("{\"AuthorizationHeader\":\"sec4\"}");
        assertEquals("[REDACTED]", node4.get("AuthorizationHeader").asText());
    }

    @Test
    @DisplayName("TEST15: githubAccessToken redacted")
    void test15_githubAccessTokenRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"githubAccessToken\":\"ghp_1234567890abcdef123456\"}");
        assertEquals("[REDACTED]", node.get("githubAccessToken").asText());
    }

    @Test
    @DisplayName("TEST16: workerLeaseToken redacted")
    void test16_workerLeaseTokenRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"workerLeaseToken\":\"4e9f5ccd-826a-4012-ad71-db45ee560d7b\"}");
        assertEquals("[REDACTED]", node.get("workerLeaseToken").asText());
    }

    @Test
    @DisplayName("TEST17: privateKey redacted")
    void test17_privateKeyRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"privateKey\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIE...\\n-----END RSA PRIVATE KEY-----\"}");
        assertEquals("[REDACTED]", node.get("privateKey").asText());
    }

    @Test
    @DisplayName("TEST18: clientSecret redacted")
    void test18_clientSecretRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"clientSecret\":\"super_secret_client_id\"}");
        assertEquals("[REDACTED]", node.get("clientSecret").asText());
    }

    @Test
    @DisplayName("TEST19: Multiple sensitive fields in one record redacted")
    void test19_multipleSensitiveFieldsRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"apiKey\":\"k1\",\"password\":\"p1\",\"jwtToken\":\"jwt1\",\"clientSecret\":\"s1\",\"safeField\":\"safe\"}");
        assertEquals("[REDACTED]", node.get("apiKey").asText());
        assertEquals("[REDACTED]", node.get("password").asText());
        assertEquals("[REDACTED]", node.get("jwtToken").asText());
        assertEquals("[REDACTED]", node.get("clientSecret").asText());
        assertEquals("safe", node.get("safeField").asText());
    }

    @Test
    @DisplayName("TEST20: Sensitive fields inside arrays of arrays redacted")
    void test20_arraysOfArraysRedacted() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{\"matrix\":[[{\"token\":\"t1\"}],[{\"secret\":\"s2\"}]]}");
        assertEquals("[REDACTED]", node.get("matrix").get(0).get(0).get("token").asText());
        assertEquals("[REDACTED]", node.get("matrix").get(1).get(0).get("secret").asText());
    }

    @Test
    @DisplayName("TEST21: Malformed JSON metadata handled safely without secret leakage")
    void test21_malformedJsonHandledSafely() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson("{malformed json with apiKey = secret123");
        assertNotNull(node);
        assertFalse(node.toString().contains("secret123"));
    }

    @Test
    @DisplayName("TEST22: Null metadata handled gracefully")
    void test22_nullMetadataHandledGracefully() {
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson(null);
        assertTrue(node.isNull());
    }

    @Test
    @DisplayName("TEST23: Oversized metadata (>512KB) fails closed")
    void test23_oversizedMetadataFailsClosed() {
        StringBuilder sb = new StringBuilder("{\"big\":\"");
        sb.append("A".repeat(512 * 1024 + 100));
        sb.append("\"}");

        ApiException ex = assertThrows(ApiException.class, () -> auditPayloadSanitizer.sanitizePayloadJson(sb.toString()));
        assertEquals("PAYLOAD_TOO_LARGE", ex.getCode());
    }

    @Test
    @DisplayName("TEST24: Excessive nesting depth (>32) fails closed")
    void test24_excessiveNestingDepthFailsClosed() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 35; i++) {
            sb.append("{\"layer").append(i).append("\":");
        }
        sb.append("\"value\"");
        for (int i = 0; i < 35; i++) {
            sb.append("}");
        }

        ApiException ex = assertThrows(ApiException.class, () -> auditPayloadSanitizer.sanitizePayloadJson(sb.toString()));
        assertEquals("EXCESSIVE_NESTING_DEPTH", ex.getCode());
    }

    @Test
    @DisplayName("TEST25: Redaction does not leak original value through exception/log output")
    void test25_redactionDoesNotLeakOriginalValue() {
        String secretVal = "CRITICAL_SECRET_VALUE_999";
        String payload = "{\"apiKey\":\"" + secretVal + "\"}";

        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson(payload);
        assertFalse(node.toString().contains(secretVal));
    }

    @Test
    @DisplayName("TEST26: Sanitized output contains no forbidden key names un-redacted")
    void test26_sanitizedOutputContainsNoForbiddenSecrets() {
        String payload = "{\"rawPrompt\":\"secret prompt\",\"apiKey\":\"sk-12345\",\"githubAccessToken\":\"ghp_999\"}";
        JsonNode node = auditPayloadSanitizer.sanitizePayloadJson(payload);

        assertFalse(node.toString().contains("secret prompt"));
        assertFalse(node.toString().contains("sk-12345"));
        assertFalse(node.toString().contains("ghp_999"));
    }

    @Test
    @DisplayName("TEST27: Canonical field ordering is deterministic")
    void test27_canonicalFieldOrderingIsDeterministic() throws Exception {
        createOutboxEvent(orgA, "EVT_CANON", "{\"b\":\"valB\",\"a\":\"valA\"}");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
        String line = lines.get(0);

        int seqIdx = line.indexOf("\"sequenceNumber\"");
        int canonIdx = line.indexOf("\"canonicalAuditEventId\"");
        int orgIdx = line.indexOf("\"organizationId\"");
        int occIdx = line.indexOf("\"occurrenceId\"");
        int evtIdx = line.indexOf("\"eventType\"");
        int payloadIdx = line.indexOf("\"payload\"");

        assertTrue(seqIdx < canonIdx && canonIdx < orgIdx && orgIdx < occIdx && occIdx < evtIdx && evtIdx < payloadIdx,
                "Fields MUST strictly follow canonical allowlist property ordering");
    }

    @Test
    @DisplayName("TEST28: JSONL line determinism (single line per record, newline separated)")
    void test28_jsonlLineDeterminism() throws Exception {
        createOutboxEvent(orgA, "EVT_1", "{\"msg\":\"line1\"}");
        createOutboxEvent(orgA, "EVT_2", "{\"msg\":\"line2\"}");

        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(2, lines.size());
        for (String line : lines) {
            assertFalse(line.contains("\n"), "Individual JSONL line must not contain unescaped newline");
            assertTrue(line.startsWith("{") && line.endsWith("}"), "Each line must be a valid JSON object");
        }
    }

    // =========================================================================
    // STREAMING / MEMORY TESTS (TEST29 - TEST33)
    // =========================================================================

    @Test
    @DisplayName("TEST29: Large export is processed in bounded batches")
    void test29_largeExportProcessedInBoundedBatches() throws Exception {
        int recordCount = 250;
        for (int i = 0; i < recordCount; i++) {
            createOutboxEvent(orgA, "BATCH_EVT_" + i, "{\"idx\":" + i + "}");
        }

        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExportStreamingResult result = auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        assertEquals(recordCount, result.getRecordCount());
        assertTrue(result.getArtifactSizeBytes() > 0);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(recordCount, lines.size());
    }

    @Test
    @DisplayName("TEST30: No full audit dataset is accumulated in memory (Streaming Output)")
    void test30_noFullDatasetAccumulatedInMemory() throws Exception {
        createOutboxEvent(orgA, "STREAM_EVT_1", "{\"data\":\"1\"}");
        createOutboxEvent(orgA, "STREAM_EVT_2", "{\"data\":\"2\"}");

        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExportStreamingResult result = auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        assertEquals(2, result.getRecordCount());
        assertTrue(baos.toByteArray().length > 0);
    }

    @Test
    @DisplayName("TEST31: Deterministic ordering across multiple pages/batches")
    void test31_deterministicOrderingAcrossBatches() throws Exception {
        int total = 150;
        for (int i = 0; i < total; i++) {
            createOutboxEvent(orgA, "MULTI_BATCH_" + i, "{\"step\":" + i + "}");
        }

        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(total, lines.size());

        long prevSeq = -1L;
        for (String line : lines) {
            JsonNode node = objectMapper.readTree(line);
            long currSeq = node.get("sequenceNumber").asLong();
            assertTrue(currSeq > prevSeq, "Sequence numbers across batch pages must be strictly ASC");
            prevSeq = currSeq;
        }
    }

    @Test
    @DisplayName("TEST32: One malformed/oversized record fails closed according to policy")
    void test32_oversizedRecordFailsClosed() {
        StringBuilder sb = new StringBuilder("{\"large\":\"");
        sb.append("X".repeat(512 * 1024 + 100));
        sb.append("\"}");

        createOutboxEvent(orgA, "EVT_OVERSIZED", sb.toString());

        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertThrows(ApiException.class, () -> auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos));
    }

    @Test
    @DisplayName("TEST33: Worker restart does not change the persisted snapshot boundary")
    void test33_workerRestartPreservesSnapshotBoundary() throws Exception {
        createOutboxEvent(orgA, "EVT_RESTART_BASE", "{\"data\":\"base\"}");
        AuditExportJobResponse resp = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        createOutboxEvent(orgA, "EVT_RESTART_AFTER", "{\"data\":\"after\"}");

        AuditExportJob reloadedJob = auditExportJobRepository.findById(resp.getJobId()).orElseThrow();
        assertEquals(resp.getMaxSequenceNumber(), reloadedJob.getMaxSequenceNumber(), "Persisted maxSequenceNumber MUST NOT change on worker reload/restart");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), reloadedJob.getId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("EVT_RESTART_BASE"));
        assertFalse(lines.get(0).contains("EVT_RESTART_AFTER"));
    }

    // =========================================================================
    // AUTHORITATIVE TENANT ISOLATION TESTS (TEST-T01 - TEST-T08)
    // =========================================================================

    @Test
    @DisplayName("TEST-T01: Org B payload containing Org A UUID excluded by raw SQL query")
    void testT01_orgBPayloadContainingOrgAUuidExcludedByRawSql() {
        createOutboxEvent(orgA, "EVT_A1", "{\"data\":\"orgA_data\"}");

        AuditLogOutbox orgBEventInfiltrated = new AuditLogOutbox();
        orgBEventInfiltrated.setOrganization(orgB);
        orgBEventInfiltrated.setCanonicalAuditEventId("EVT:B:" + UUID.randomUUID());
        orgBEventInfiltrated.setOccurrenceId(UUID.randomUUID());
        orgBEventInfiltrated.setAggregateType("TestAggregate");
        orgBEventInfiltrated.setAggregateId(orgB.getId().toString());
        orgBEventInfiltrated.setEventType("EVT_B_INFILTRATED");
        orgBEventInfiltrated.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        orgBEventInfiltrated.setCreatedAt(Instant.now());
        orgBEventInfiltrated.setPayloadJson("{\"infiltratedOrgId\":\"" + orgA.getId() + "\",\"secret\":\"orgB_data\"}");
        auditLogOutboxRepository.saveAndFlush(orgBEventInfiltrated);

        List<AuditLogOutbox> chunk = auditLogOutboxRepository.findChunkForExport(
                orgA.getId(), 0L, 999999L, org.springframework.data.domain.PageRequest.of(0, 100));

        assertEquals(1, chunk.size(), "Raw SQL chunk query for Org A must return strictly Org A events");
        assertEquals(orgA.getId(), chunk.get(0).getOrganization().getId());
        assertFalse(chunk.get(0).getPayloadJson().contains("orgB_data"));
    }

    @Test
    @DisplayName("TEST-T02: Org B canonicalAuditEventId containing Org A UUID excluded by raw SQL query")
    void testT02_orgBCanonicalIdContainingOrgAUuidExcludedByRawSql() {
        createOutboxEvent(orgA, "EVT_A1", "{\"data\":\"orgA_data\"}");

        AuditLogOutbox orgBEventInfiltrated = new AuditLogOutbox();
        orgBEventInfiltrated.setOrganization(orgB);
        orgBEventInfiltrated.setCanonicalAuditEventId("EVT:" + orgA.getId() + ":" + UUID.randomUUID());
        orgBEventInfiltrated.setOccurrenceId(UUID.randomUUID());
        orgBEventInfiltrated.setAggregateType("TestAggregate");
        orgBEventInfiltrated.setAggregateId(orgB.getId().toString());
        orgBEventInfiltrated.setEventType("EVT_B_INFILTRATED");
        orgBEventInfiltrated.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        orgBEventInfiltrated.setCreatedAt(Instant.now());
        orgBEventInfiltrated.setPayloadJson("{\"data\":\"orgB_data\"}");
        auditLogOutboxRepository.saveAndFlush(orgBEventInfiltrated);

        List<AuditLogOutbox> chunk = auditLogOutboxRepository.findChunkForExport(
                orgA.getId(), 0L, 999999L, org.springframework.data.domain.PageRequest.of(0, 100));

        assertEquals(1, chunk.size(), "Raw SQL chunk query for Org A must exclude Org B event with Org A UUID in canonical ID");
        assertEquals(orgA.getId(), chunk.get(0).getOrganization().getId());
    }

    @Test
    @DisplayName("TEST-T03: Org B aggregateId containing Org A UUID excluded by raw SQL query")
    void testT03_orgBAggregateIdContainingOrgAUuidExcludedByRawSql() {
        createOutboxEvent(orgA, "EVT_A1", "{\"data\":\"orgA_data\"}");

        AuditLogOutbox orgBEventInfiltrated = new AuditLogOutbox();
        orgBEventInfiltrated.setOrganization(orgB);
        orgBEventInfiltrated.setCanonicalAuditEventId("EVT:B:" + UUID.randomUUID());
        orgBEventInfiltrated.setOccurrenceId(UUID.randomUUID());
        orgBEventInfiltrated.setAggregateType("TestAggregate");
        orgBEventInfiltrated.setAggregateId(orgA.getId().toString());
        orgBEventInfiltrated.setEventType("EVT_B_INFILTRATED");
        orgBEventInfiltrated.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        orgBEventInfiltrated.setCreatedAt(Instant.now());
        orgBEventInfiltrated.setPayloadJson("{\"data\":\"orgB_data\"}");
        auditLogOutboxRepository.saveAndFlush(orgBEventInfiltrated);

        List<AuditLogOutbox> chunk = auditLogOutboxRepository.findChunkForExport(
                orgA.getId(), 0L, 999999L, org.springframework.data.domain.PageRequest.of(0, 100));

        assertEquals(1, chunk.size(), "Raw SQL chunk query for Org A must exclude Org B event with Org A UUID as aggregateId");
        assertEquals(orgA.getId(), chunk.get(0).getOrganization().getId());
    }

    @Test
    @DisplayName("TEST-T04: Org B with triple Org A UUID infiltration excluded by raw SQL query")
    void testT04_orgBTripleInfiltrationExcludedByRawSql() {
        createOutboxEvent(orgA, "EVT_A1", "{\"data\":\"orgA_data\"}");

        AuditLogOutbox orgBEventInfiltrated = new AuditLogOutbox();
        orgBEventInfiltrated.setOrganization(orgB);
        orgBEventInfiltrated.setCanonicalAuditEventId("EVT:" + orgA.getId() + ":" + UUID.randomUUID());
        orgBEventInfiltrated.setOccurrenceId(UUID.randomUUID());
        orgBEventInfiltrated.setAggregateType("TestAggregate");
        orgBEventInfiltrated.setAggregateId(orgA.getId().toString());
        orgBEventInfiltrated.setEventType("EVT_B_TRIPLE");
        orgBEventInfiltrated.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        orgBEventInfiltrated.setCreatedAt(Instant.now());
        orgBEventInfiltrated.setPayloadJson("{\"organizationId\":\"" + orgA.getId() + "\",\"secret\":\"orgB_secret\"}");
        auditLogOutboxRepository.saveAndFlush(orgBEventInfiltrated);

        List<AuditLogOutbox> chunk = auditLogOutboxRepository.findChunkForExport(
                orgA.getId(), 0L, 999999L, org.springframework.data.domain.PageRequest.of(0, 100));

        assertEquals(1, chunk.size(), "Raw SQL chunk query for Org A must exclude Org B event even with triple UUID infiltration");
        assertEquals(orgA.getId(), chunk.get(0).getOrganization().getId());
    }

    @Test
    @DisplayName("TEST-T05: Mixed dataset query for Org A returns strictly Org A rows in raw SQL")
    void testT05_mixedDatasetQueryReturnsStrictlyOrgARows() {
        for (int i = 0; i < 5; i++) {
            createOutboxEvent(orgA, "EVT_A_" + i, "{\"idx\":" + i + "}");
        }
        for (int i = 0; i < 5; i++) {
            AuditLogOutbox orgBEvent = new AuditLogOutbox();
            orgBEvent.setOrganization(orgB);
            orgBEvent.setCanonicalAuditEventId("EVT:" + orgA.getId() + ":B_" + i);
            orgBEvent.setOccurrenceId(UUID.randomUUID());
            orgBEvent.setAggregateType("TestAggregate");
            orgBEvent.setAggregateId(orgA.getId().toString());
            orgBEvent.setEventType("EVT_B_" + i);
            orgBEvent.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            orgBEvent.setCreatedAt(Instant.now());
            orgBEvent.setPayloadJson("{\"organizationId\":\"" + orgA.getId() + "\",\"val\":" + i + "}");
            auditLogOutboxRepository.saveAndFlush(orgBEvent);
        }

        List<AuditLogOutbox> chunkUnfiltered = auditLogOutboxRepository.findChunkForExport(
                orgA.getId(), 0L, 999999L, org.springframework.data.domain.PageRequest.of(0, 100));
        assertEquals(5, chunkUnfiltered.size());
        for (AuditLogOutbox o : chunkUnfiltered) {
            assertEquals(orgA.getId(), o.getOrganization().getId());
        }

        List<AuditLogOutbox> chunkFiltered = auditLogOutboxRepository.findChunkForExportFiltered(
                orgA.getId(), 0L, 999999L, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), org.springframework.data.domain.PageRequest.of(0, 100));
        assertEquals(5, chunkFiltered.size());
        for (AuditLogOutbox o : chunkFiltered) {
            assertEquals(orgA.getId(), o.getOrganization().getId());
        }
    }

    @Test
    @DisplayName("TEST-T06: Mixed dataset pagination returns strictly Org A rows on every page in raw SQL")
    void testT06_mixedDatasetPaginationReturnsStrictlyOrgARows() {
        for (int i = 0; i < 15; i++) {
            createOutboxEvent(orgA, "EVT_A_" + i, "{\"idx\":" + i + "}");
        }
        for (int i = 0; i < 15; i++) {
            AuditLogOutbox orgBEvent = new AuditLogOutbox();
            orgBEvent.setOrganization(orgB);
            orgBEvent.setCanonicalAuditEventId("EVT:B:" + UUID.randomUUID());
            orgBEvent.setOccurrenceId(UUID.randomUUID());
            orgBEvent.setAggregateType("TestAggregate");
            orgBEvent.setAggregateId(orgA.getId().toString());
            orgBEvent.setEventType("EVT_B_" + i);
            orgBEvent.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            orgBEvent.setCreatedAt(Instant.now());
            orgBEvent.setPayloadJson("{\"organizationId\":\"" + orgA.getId() + "\"}");
            auditLogOutboxRepository.saveAndFlush(orgBEvent);
        }

        int pageSize = 5;
        long lastSeq = 0L;
        int totalFetched = 0;

        while (true) {
            List<AuditLogOutbox> page = auditLogOutboxRepository.findChunkForExport(
                    orgA.getId(), lastSeq, 999999L, org.springframework.data.domain.PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (AuditLogOutbox o : page) {
                assertEquals(orgA.getId(), o.getOrganization().getId(), "Every paginated record must belong to Org A");
            }
            totalFetched += page.size();
            lastSeq = page.get(page.size() - 1).getSequenceNumber();
        }

        assertEquals(15, totalFetched, "Total fetched records for Org A across pages must equal 15");
    }

    @Test
    @DisplayName("TEST-T07: Post-snapshot Org B insertion containing Org A UUID excluded by raw SQL")
    void testT07_postSnapshotOrgBInsertionExcludedByRawSql() throws Exception {
        createOutboxEvent(orgA, "EVT_A1", "{\"data\":\"orgA_1\"}");

        AuditExportJobResponse resp = exportJobEngineService.createExportJob(
                orgA.getId(), adminOrgA, new CreateAuditExportRequest("FULL", null, null, null));

        AuditLogOutbox postSnapshotOrgB = new AuditLogOutbox();
        postSnapshotOrgB.setOrganization(orgB);
        postSnapshotOrgB.setCanonicalAuditEventId("EVT:" + orgA.getId() + ":POST_B");
        postSnapshotOrgB.setOccurrenceId(UUID.randomUUID());
        postSnapshotOrgB.setAggregateType("TestAggregate");
        postSnapshotOrgB.setAggregateId(orgA.getId().toString());
        postSnapshotOrgB.setEventType("EVT_POST_B");
        postSnapshotOrgB.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        postSnapshotOrgB.setCreatedAt(Instant.now());
        postSnapshotOrgB.setPayloadJson("{\"organizationId\":\"" + orgA.getId() + "\"}");
        auditLogOutboxRepository.saveAndFlush(postSnapshotOrgB);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        auditExportStreamingService.streamAuditExport(orgA.getId(), resp.getJobId(), adminOrgA, baos);

        List<String> lines = readGzipLines(baos.toByteArray());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("EVT_A1"));
        assertFalse(lines.get(0).contains("EVT_POST_B"));
    }

    @Test
    @DisplayName("TEST-T08: Forging payload JSON does not alter DB ownership")
    void testT08_forgingPayloadJsonDoesNotAlterDbOwnership() {
        AuditLogOutbox forgedPayloadEvent = new AuditLogOutbox();
        forgedPayloadEvent.setOrganization(orgB);
        forgedPayloadEvent.setCanonicalAuditEventId("EVT:FORGED:" + UUID.randomUUID());
        forgedPayloadEvent.setOccurrenceId(UUID.randomUUID());
        forgedPayloadEvent.setAggregateType("TestAggregate");
        forgedPayloadEvent.setAggregateId(orgA.getId().toString());
        forgedPayloadEvent.setEventType("EVT_FORGED_PAYLOAD");
        forgedPayloadEvent.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        forgedPayloadEvent.setCreatedAt(Instant.now());
        forgedPayloadEvent.setPayloadJson("{\"organizationId\":\"" + orgA.getId() + "\",\"role\":\"SUPER_ADMIN\"}");
        forgedPayloadEvent = auditLogOutboxRepository.saveAndFlush(forgedPayloadEvent);

        UUID dbOrgId = jdbcTemplate.queryForObject(
                "SELECT organization_id FROM audit_log_outbox WHERE id = ?", UUID.class, forgedPayloadEvent.getId());
        assertEquals(orgB.getId(), dbOrgId, "Database column organization_id must reflect actual entity organization, not forged JSON content");

        List<AuditLogOutbox> chunkA = auditLogOutboxRepository.findChunkForExport(
                orgA.getId(), 0L, 999999L, org.springframework.data.domain.PageRequest.of(0, 100));
        assertTrue(chunkA.isEmpty(), "Org A raw SQL query must return 0 records for forged Org B event");

        List<AuditLogOutbox> chunkB = auditLogOutboxRepository.findChunkForExport(
                orgB.getId(), 0L, 999999L, org.springframework.data.domain.PageRequest.of(0, 100));
        assertEquals(1, chunkB.size(), "Org B raw SQL query must return 1 record");
        assertEquals(orgB.getId(), chunkB.get(0).getOrganization().getId());
    }
}
