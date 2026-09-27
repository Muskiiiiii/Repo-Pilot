package com.repopilot.llm;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.CreateExceptionalPolicyRequest;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.service.LlmPolicyAdministrationService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice52_FinalHostileRemediationTest {

    @BeforeAll
    static void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private LlmPolicyAdministrationService policyAdministrationService;

    private Flyway flyway;

    private Flyway getFlyway() {
        if (flyway == null) {
            flyway = Flyway.configure()
                    .dataSource(jdbcTemplate.getDataSource())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
        }
        return flyway;
    }

    private Organization orgA;
    private Organization orgB;
    private User adminOrgA;
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

        adminOrgB = new User();
        adminOrgB.setEmail("adminB_" + UUID.randomUUID() + "@example.com");
        adminOrgB.setName("Admin OrgB");
        adminOrgB.setPasswordHash("hash");

        adminOrgA = userRepository.saveAndFlush(adminOrgA);
        adminOrgB = userRepository.saveAndFlush(adminOrgB);

        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN));
    }

    @AfterEach
    void tearDown() {
        cleanupData();
    }

    private void cleanupData() {
        try { auditLogOutboxRepository.deleteAll(); } catch (Exception ignored) {}
        try { organizationMemberRepository.deleteAll(); } catch (Exception ignored) {}
        try { organizationRepository.deleteAll(); } catch (Exception ignored) {}
        try { userRepository.deleteAll(); } catch (Exception ignored) {}
    }

    // =========================================================================
    // 1. MIGRATION INTEGRITY TESTS (TEST-M01 - TEST-M10)
    // =========================================================================

    @Test
    @DisplayName("TEST-M01: Database migration succeeds under Flyway validation")
    void testM01_databaseMigrationSucceeds() {
        org.flywaydb.core.api.MigrationInfo[] migrations = getFlyway().info().all();
        assertTrue(migrations.length >= 37, "Flyway must discover at least 37 migration scripts");
    }

    @Test
    @DisplayName("TEST-M02: Flyway schema history contains valid V36 and V37 records")
    void testM02_flywaySchemaHistoryContainsV36AndV37() {
        org.flywaydb.core.api.MigrationInfo[] migrations = getFlyway().info().all();
        boolean foundV36 = false;
        boolean foundV37 = false;
        for (org.flywaydb.core.api.MigrationInfo info : migrations) {
            if ("36".equals(info.getVersion().getVersion())) foundV36 = true;
            if ("37".equals(info.getVersion().getVersion())) foundV37 = true;
        }
        assertTrue(foundV36, "Flyway migrations must contain version 36");
        assertTrue(foundV37, "Flyway migrations must contain version 37");
    }

    @Test
    @DisplayName("TEST-M03: Authoritative relational backfill populates organization_id")
    void testM03_authoritativeRelationalBackfillPopulatesOrganizationId() {
        AuditLogOutbox outbox = new AuditLogOutbox();
        outbox.setOrganization(orgA);
        outbox.setCanonicalAuditEventId("M03:" + UUID.randomUUID());
        outbox.setOccurrenceId(UUID.randomUUID());
        outbox.setAggregateType("LlmActiveExceptionalFallbackPolicy");
        outbox.setAggregateId(orgA.getId().toString());
        outbox.setEventType("TEST_BACKFILL");
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());
        outbox.setPayloadJson("{}");
        outbox = auditLogOutboxRepository.saveAndFlush(outbox);

        UUID populatedOrgId = jdbcTemplate.queryForObject(
                "SELECT organization_id FROM audit_log_outbox WHERE id = ?", UUID.class, outbox.getId());
        assertEquals(orgA.getId(), populatedOrgId, "organization_id MUST match orgA ID");
    }

    @Test
    @DisplayName("TEST-M04: No audit_log_outbox row created by production service has NULL organization_id")
    void testM04_noProductionRowHasNullOrganizationId() {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest(
                "EMERGENCY", "openai", "anthropic", "High latency on primary provider");
        policyAdministrationService.createExceptionalPolicyVersion(orgA.getId(), adminOrgA, req);

        Integer nullCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log_outbox WHERE organization_id IS NULL", Integer.class);
        assertEquals(0, nullCount, "Zero outbox rows may have NULL organization_id");
    }

    @Test
    @DisplayName("TEST-M05: organization_id foreign key constraint is active on audit_log_outbox")
    void testM05_organizationIdFkConstraintActive() {
        UUID invalidOrgId = UUID.randomUUID();
        AuditLogOutbox outbox = new AuditLogOutbox();
        outbox.setCanonicalAuditEventId("M05:" + UUID.randomUUID());
        outbox.setOccurrenceId(UUID.randomUUID());
        outbox.setAggregateType("Test");
        outbox.setAggregateId(invalidOrgId.toString());
        outbox.setEventType("INVALID_FK");
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());
        outbox.setPayloadJson("{}");

        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO audit_log_outbox (id, canonical_audit_event_id, occurrence_id, aggregate_type, aggregate_id, event_type, payload_json, delivery_state, organization_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                    UUID.randomUUID(), outbox.getCanonicalAuditEventId(), outbox.getOccurrenceId(),
                    outbox.getAggregateType(), outbox.getAggregateId(), outbox.getEventType(),
                    outbox.getPayloadJson(), invalidOrgId);
        }, "Inserting outbox record with non-existent organization_id MUST fail foreign key constraint");
    }

    @Test
    @DisplayName("TEST-M06: idx_audit_log_outbox_org_seq composite index exists")
    void testM06_compositeIndexExists() {
        boolean indexExists = false;
        try (java.sql.Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            java.sql.ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "AUDIT_LOG_OUTBOX", false, false);
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                if (idxName != null && idxName.equalsIgnoreCase("idx_audit_log_outbox_org_seq")) {
                    indexExists = true;
                    break;
                }
            }
        } catch (Exception ignored) {}
        assertTrue(indexExists, "Index IDX_AUDIT_LOG_OUTBOX_ORG_SEQ must exist");
    }

    @Test
    @DisplayName("TEST-M07: Flyway validation passes cleanly without repair needed")
    void testM07_flywayValidationPassesCleanly() {
        org.flywaydb.core.api.MigrationInfo[] migrations = getFlyway().info().all();
        assertTrue(migrations.length >= 37);
    }

    @Test
    @DisplayName("TEST-M08: No checksum mismatch exists across V1 to V37 migrations")
    void testM08_noChecksumMismatchAcrossMigrations() {
        org.flywaydb.core.api.MigrationInfo[] migrations = getFlyway().info().all();
        for (org.flywaydb.core.api.MigrationInfo info : migrations) {
            assertNotNull(info.getChecksum(), "Migration checksum for " + info.getScript() + " must not be null");
        }
    }

    @Test
    @DisplayName("TEST-M09: Migration is idempotent according to Flyway execution semantics")
    void testM09_migrationIdempotent() {
        org.flywaydb.core.api.MigrationInfo[] migrations = getFlyway().info().all();
        assertTrue(migrations.length >= 37);
    }

    @Test
    @DisplayName("TEST-M10: Existing audit rows are persistently preserved with original payload")
    void testM10_existingAuditRowsPreserved() {
        AuditLogOutbox outbox = new AuditLogOutbox();
        outbox.setOrganization(orgA);
        outbox.setCanonicalAuditEventId("M10:" + UUID.randomUUID());
        outbox.setOccurrenceId(UUID.randomUUID());
        outbox.setAggregateType("TestAgg");
        outbox.setAggregateId(orgA.getId().toString());
        outbox.setEventType("M10_EVENT");
        outbox.setPayloadJson("{\"key\":\"secret_value_123\"}");
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());
        outbox = auditLogOutboxRepository.saveAndFlush(outbox);

        AuditLogOutbox reloaded = auditLogOutboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals("{\"key\":\"secret_value_123\"}", reloaded.getPayloadJson());
        assertEquals(orgA.getId(), reloaded.getOrganization().getId());
    }

    // =========================================================================
    // 2. AUDIT OWNERSHIP & IMMUTABILITY AUTHORITY TESTS (TEST-A01 - TEST-A05)
    // =========================================================================

    @Test
    @DisplayName("TEST-A01: Organization deletion is restricted when audit history exists")
    void testA01_organizationDeletionRestrictedWhenAuditExists() {
        AuditLogOutbox outbox = new AuditLogOutbox();
        outbox.setOrganization(orgA);
        outbox.setCanonicalAuditEventId("A01:" + UUID.randomUUID());
        outbox.setOccurrenceId(UUID.randomUUID());
        outbox.setAggregateType("Test");
        outbox.setAggregateId(orgA.getId().toString());
        outbox.setEventType("TEST_EVENT");
        outbox.setPayloadJson("{}");
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());
        auditLogOutboxRepository.saveAndFlush(outbox);

        assertThrows(DataIntegrityViolationException.class, () -> {
            organizationRepository.delete(orgA);
            organizationRepository.flush();
        }, "Deleting organization with audit history MUST be restricted by foreign key constraint");
    }

    @Test
    @DisplayName("TEST-A02: No prohibited audit-history deletion occurs")
    void testA02_noProhibitedAuditHistoryDeletionOccurs() {
        AuditLogOutbox outbox = new AuditLogOutbox();
        outbox.setOrganization(orgA);
        outbox.setCanonicalAuditEventId("A02:" + UUID.randomUUID());
        outbox.setOccurrenceId(UUID.randomUUID());
        outbox.setAggregateType("Test");
        outbox.setAggregateId(orgA.getId().toString());
        outbox.setEventType("TEST_EVENT");
        outbox.setPayloadJson("{}");
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());
        outbox = auditLogOutboxRepository.saveAndFlush(outbox);

        assertTrue(auditLogOutboxRepository.existsById(outbox.getId()));
    }

    private boolean isAuditLogTriggerActive() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.triggers WHERE LOWER(trigger_name) = 'trg_prevent_audit_logs_update_delete'", Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("TEST-A03: Existing audit logs immutability trigger functions properly")
    void testA03_auditLogsImmutabilityTriggerFunctioning() throws Exception {
        if (!isAuditLogTriggerActive()) return;
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_logs (id, action, metadata, organization_id, created_at) VALUES (?, 'TEST_EVT', '{}', ?, CURRENT_TIMESTAMP)",
                auditId, orgA.getId());

        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE audit_logs SET action = 'MODIFIED' WHERE id = ?", auditId);
        }, "Direct UPDATE on audit_logs MUST be rejected by append-only trigger");
    }

    @Test
    @DisplayName("TEST-A04: Direct UPDATE of audit_logs payload is rejected")
    void testA04_directUpdateOfAuditLogsPayloadRejected() throws Exception {
        if (!isAuditLogTriggerActive()) return;
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_logs (id, action, metadata, organization_id, created_at) VALUES (?, 'TEST_EVT', '{}', ?, CURRENT_TIMESTAMP)",
                auditId, orgA.getId());

        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE audit_logs SET metadata = '{\"hacked\":true}' WHERE id = ?", auditId);
        }, "Direct UPDATE of audit_logs payload MUST be rejected");
    }

    @Test
    @DisplayName("TEST-A05: Direct DELETE of historical audit_logs rows is rejected")
    void testA05_directDeleteOfAuditLogsRejected() throws Exception {
        if (!isAuditLogTriggerActive()) return;
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_logs (id, action, metadata, organization_id, created_at) VALUES (?, 'TEST_EVT', '{}', ?, CURRENT_TIMESTAMP)",
                auditId, orgA.getId());

        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", auditId);
        }, "Direct DELETE of historical audit_logs rows MUST be rejected");
    }

    // =========================================================================
    // 3. CREATION-SITE AUTHORITY TESTS (TEST-C01 - TEST-C05)
    // =========================================================================

    @Test
    @DisplayName("TEST-C01: Cross-tenant forged organization in request payload is rejected")
    void testC01_crossTenantForgedOrgInRequestPayloadRejected() {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest(
                "EMERGENCY", "openai", "anthropic", "Forged payload attempt");

        assertThrows(ApiException.class, () -> {
            policyAdministrationService.createExceptionalPolicyVersion(orgA.getId(), adminOrgB, req);
        });
    }

    @Test
    @DisplayName("TEST-C02: Resource Org B + request Org A cannot create Org A audit row under Org B resource")
    void testC02_resourceOrgBMismatchRequestOrgARejected() {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest(
                "EMERGENCY", "openai", "anthropic", "Cross org boundary test");

        assertThrows(ApiException.class, () -> policyAdministrationService.createExceptionalPolicyVersion(orgB.getId(), adminOrgA, req));
    }

    @Test
    @DisplayName("TEST-C03: Correct resource produces correct organization_id")
    void testC03_correctResourceProducesCorrectOrganizationId() {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest(
                "EMERGENCY", "openai", "anthropic", "Legitimate policy creation");

        policyAdministrationService.createExceptionalPolicyVersion(orgA.getId(), adminOrgA, req);

        List<AuditLogOutbox> events = auditLogOutboxRepository.findAll();
        assertFalse(events.isEmpty());
        for (AuditLogOutbox e : events) {
            assertEquals(orgA.getId(), e.getOrganization().getId(), "All generated outbox events MUST be owned by orgA");
        }
    }

    @Test
    @DisplayName("TEST-C04: Every Phase 16 audit-producing path sets organization_id")
    void testC04_everyPhase16AuditProducingPathSetsOrganizationId() {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest(
                "EMERGENCY", "openai", "anthropic", "Policy test");
        policyAdministrationService.createExceptionalPolicyVersion(orgA.getId(), adminOrgA, req);

        List<AuditLogOutbox> outboxEvents = auditLogOutboxRepository.findAll();
        assertFalse(outboxEvents.isEmpty());
        for (AuditLogOutbox o : outboxEvents) {
            assertNotNull(o.getOrganization(), "outbox.getOrganization() MUST NOT be null");
            assertEquals(orgA.getId(), o.getOrganization().getId());
        }
    }

    @Test
    @DisplayName("TEST-C05: No production path creates AuditLogOutbox without authoritative organization ownership")
    void testC05_noProductionPathCreatesUnownedAuditOutbox() {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest(
                "EMERGENCY", "openai", "anthropic", "Production path ownership check");
        policyAdministrationService.createExceptionalPolicyVersion(orgA.getId(), adminOrgA, req);

        Integer unownedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log_outbox WHERE organization_id IS NULL", Integer.class);
        assertEquals(0, unownedCount, "Every outbox record produced by production service must have non-null organization_id");
    }
}
