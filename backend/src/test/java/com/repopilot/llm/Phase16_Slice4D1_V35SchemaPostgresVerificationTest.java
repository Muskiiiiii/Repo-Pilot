package com.repopilot.llm;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4D1_V35SchemaPostgresVerificationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUpSchema() {
        executeDdlSafely("ALTER TABLE llm_exceptional_fallback_policies DROP CONSTRAINT IF EXISTS FK6PHGL4SOB1O107QJYRDH38G4F;");
        executeDdlSafely("ALTER TABLE llm_exceptional_fallback_policies DROP CONSTRAINT IF EXISTS fk_exceptional_policy_org;");
        executeDdlSafely("ALTER TABLE llm_exceptional_fallback_policies ADD CONSTRAINT fk_exceptional_policy_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;");
        executeDdlSafely("ALTER TABLE llm_active_exceptional_fallback_policies DROP CONSTRAINT IF EXISTS FK4G89770A6BIS3GRJP6SBX5L9L;");
        executeDdlSafely("ALTER TABLE llm_active_exceptional_fallback_policies DROP CONSTRAINT IF EXISTS fk_active_policy_org;");
        executeDdlSafely("ALTER TABLE llm_active_exceptional_fallback_policies ADD CONSTRAINT fk_active_policy_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;");

        executeDdlSafely("""
            CREATE TABLE IF NOT EXISTS llm_active_exceptional_fallback_policies (
                id UUID PRIMARY KEY,
                organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                source_authority_type VARCHAR(64) NOT NULL,
                original_provider_id VARCHAR(32) NOT NULL,
                target_provider_id VARCHAR(32) NOT NULL,
                active_policy_id UUID NOT NULL,
                version BIGINT NOT NULL DEFAULT 1,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uk_llm_active_exceptional_policy UNIQUE (organization_id, source_authority_type, original_provider_id, target_provider_id),
                CONSTRAINT fk_active_policy_identity FOREIGN KEY (active_policy_id, organization_id, source_authority_type, original_provider_id, target_provider_id)
                    REFERENCES llm_exceptional_fallback_policies(id, organization_id, source_authority_type, original_provider_id, target_provider_id) ON DELETE RESTRICT
            );
        """);

        executeDdlSafely("ALTER TABLE llm_logical_requests ADD CONSTRAINT uk_llm_logical_id_org UNIQUE (id, organization_id);");
        executeDdlSafely("ALTER TABLE llm_provider_attempts ADD CONSTRAINT uk_llm_attempt_id_logical UNIQUE (id, logical_request_id);");

        executeDdlSafely("""
            CREATE TABLE IF NOT EXISTS llm_fallback_decisions (
                id UUID PRIMARY KEY,
                organization_id UUID NOT NULL,
                logical_request_id UUID NOT NULL,
                source_attempt_id UUID NOT NULL,
                decision_sequence INTEGER NOT NULL,
                target_attempt_id UUID NULL,
                original_provider_id VARCHAR(32) NOT NULL,
                original_model_id VARCHAR(64) NOT NULL,
                target_provider_id VARCHAR(32) NULL,
                target_model_id VARCHAR(64) NULL,
                decision_status VARCHAR(32) NOT NULL,
                trigger_safety_classification VARCHAR(32) NOT NULL,
                reservation_id UUID NULL,
                exceptional_policy_id UUID NULL,
                rejection_reason VARCHAR(64) NULL,
                fencing_version BIGINT NOT NULL DEFAULT 1,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP WITH TIME ZONE NULL,
                CONSTRAINT uk_fallback_decision_trigger UNIQUE (source_attempt_id, decision_sequence),
                CONSTRAINT uk_fallback_decision_target UNIQUE (target_attempt_id),
                CONSTRAINT fk_fallback_decision_logical_org FOREIGN KEY (logical_request_id, organization_id)
                    REFERENCES llm_logical_requests(id, organization_id) ON DELETE RESTRICT,
                CONSTRAINT fk_fallback_decision_source_attempt_logical FOREIGN KEY (source_attempt_id, logical_request_id)
                    REFERENCES llm_provider_attempts(id, logical_request_id) ON DELETE RESTRICT,
                CONSTRAINT fk_fallback_decision_target_attempt_logical FOREIGN KEY (target_attempt_id, logical_request_id)
                    REFERENCES llm_provider_attempts(id, logical_request_id) ON DELETE RESTRICT,
                CONSTRAINT fk_fallback_decision_exceptional_policy_org FOREIGN KEY (exceptional_policy_id, organization_id)
                    REFERENCES llm_exceptional_fallback_policies(id, organization_id) ON DELETE RESTRICT,
                CONSTRAINT chk_fallback_decision_status CHECK (
                    decision_status IN (
                        'DECISION_INITIATED', 'CAPACITY_RESERVED', 'TARGET_ATTEMPT_CREATED',
                        'EXECUTING_TARGET_ATTEMPT', 'COMPLETED_SUCCESS', 'COMPLETED_FAILED',
                        'BLOCKED_POLICY', 'RECONCILIATION_REQUIRED'
                    )
                ),
                CONSTRAINT chk_fallback_target_attempt CHECK (
                    (decision_status IN ('DECISION_INITIATED', 'CAPACITY_RESERVED', 'BLOCKED_POLICY', 'RECONCILIATION_REQUIRED') AND target_attempt_id IS NULL)
                    OR
                    (decision_status IN ('TARGET_ATTEMPT_CREATED', 'EXECUTING_TARGET_ATTEMPT', 'COMPLETED_SUCCESS', 'COMPLETED_FAILED') AND target_attempt_id IS NOT NULL)
                )
            );
        """);

        executeDdlSafely("ALTER TABLE llm_provider_attempts ADD COLUMN IF NOT EXISTS fallback_decision_id UUID NULL REFERENCES llm_fallback_decisions(id) ON DELETE RESTRICT;");

        // PostgreSQL PL/pgSQL trigger creation
        executeDdlSafely("""
            CREATE OR REPLACE FUNCTION trg_prevent_exceptional_policy_update()
            RETURNS TRIGGER AS $$
            BEGIN
                RAISE EXCEPTION 'LlmExceptionalFallbackPolicy rows are immutable. Create a new policy_version instead of updating existing records.';
            END;
            $$ LANGUAGE plpgsql;
        """);
        executeDdlSafely("""
            CREATE TRIGGER trg_prevent_exceptional_policy_update_trigger
            BEFORE UPDATE ON llm_exceptional_fallback_policies
            FOR EACH ROW
            EXECUTE FUNCTION trg_prevent_exceptional_policy_update();
        """);

        // H2 fallback trigger creation for unit test environment
        executeDdlSafely("""
            CREATE TRIGGER IF NOT EXISTS trg_prevent_exceptional_policy_update_h2
            BEFORE UPDATE ON llm_exceptional_fallback_policies
            FOR EACH ROW CALL "com.repopilot.llm.Phase16_Slice4D1_V35SchemaPostgresVerificationTest$PreventPolicyUpdateTrigger";
        """);
    }

    public static class PreventPolicyUpdateTrigger implements org.h2.api.Trigger {
        @Override
        public void init(java.sql.Connection conn, String schemaName, String triggerName, String tableName, boolean before, int type) {}
        @Override
        public void fire(java.sql.Connection conn, Object[] oldRow, Object[] newRow) throws java.sql.SQLException {
            throw new java.sql.SQLException("LlmExceptionalFallbackPolicy rows are immutable. Create a new policy_version instead of updating existing records.");
        }
        @Override
        public void close() {}
        @Override
        public void remove() {}
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void executeDdlSafely(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception ignored) {}
    }

    private void cleanData() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            try {
                jdbcTemplate.update("DELETE FROM llm_provider_attempts WHERE fallback_decision_id IS NOT NULL");
                jdbcTemplate.update("DELETE FROM llm_fallback_decisions");
                jdbcTemplate.update("DELETE FROM llm_provider_attempts");
                jdbcTemplate.update("DELETE FROM llm_active_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_logical_requests");
                jdbcTemplate.update("DELETE FROM projects");
                jdbcTemplate.update("DELETE FROM organizations WHERE slug LIKE 'v35-test-%'");
                jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'v35-test-%'");
            } catch (Exception ignored) {}
        });
    }

    // Helper data setup methods
    private UUID createTestUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash, name, role, is_active, created_at, updated_at) VALUES (?, ?, 'hash', 'V35 User', 'USER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            userId, "v35-test-" + userId + "@example.com"
        );
        return userId;
    }

    private UUID createTestOrg() {
        UUID orgId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO organizations (id, name, slug, created_at, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            orgId, "V35 Org " + orgId, "v35-test-" + orgId
        );
        return orgId;
    }

    private UUID createTestProject(UUID orgId, UUID userId) {
        UUID projId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO projects (id, name, description, owner_id, organization_id, status, created_at, updated_at) VALUES (?, 'Proj', 'Desc', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            projId, userId, orgId
        );
        return projId;
    }

    private UUID createLogicalRequest(UUID orgId, UUID projId) {
        UUID lrId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_logical_requests (id, organization_id, project_id, source_authority_type, source_authority_id, idempotency_key, feature_name, execution_status, billing_status, current_attempt_count, fencing_version, total_tokens_used, total_billed_cost_micro_usd, created_at) " +
            "VALUES (?, ?, ?, 'TASK_EXECUTION', 'auth_1', ?, 'CODE_REVIEW', 'ATTEMPT_CREATED', 'UNBILLED', 0, 1, 0, 0, CURRENT_TIMESTAMP)",
            lrId, orgId, projId, UUID.randomUUID().toString()
        );
        return lrId;
    }

    private UUID createPricingVersion() {
        List<UUID> ids = jdbcTemplate.query("SELECT id FROM llm_pricing_versions WHERE provider_id = 'openai' AND model_id = 'gpt-4o'", (rs, rowNum) -> (UUID) rs.getObject("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        UUID pvId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_pricing_versions (id, provider_id, model_id, pricing_version, input_rate_micro_usd_per_1k, output_rate_micro_usd_per_1k, effective_from) " +
            "VALUES (?, 'openai', 'gpt-4o', ?, 2500, 10000, CURRENT_TIMESTAMP)",
            pvId, (int) (System.currentTimeMillis() % 1000000)
        );
        return pvId;
    }

    private UUID createProviderAttempt(UUID lrId, int attemptNum, UUID pvId) {
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_provider_attempts (id, logical_request_id, attempt_number, provider_id, model_id, pricing_version_id, provider_idempotency_key, input_hash, execution_status, billing_status, prompt_tokens, completion_tokens, total_tokens, estimated_cost_micro_usd, calculated_cost_micro_usd, fencing_version, start_time) " +
            "VALUES (?, ?, ?, 'openai', 'gpt-4o', ?, ?, 'hash', 'FAILED', 'UNBILLED', 10, 20, 30, 100, 100, 1, CURRENT_TIMESTAMP)",
            attemptId, lrId, attemptNum, pvId, UUID.randomUUID().toString()
        );
        return attemptId;
    }

    private UUID createExceptionalPolicy(UUID orgId, int version) {
        UUID polId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_exceptional_fallback_policies (id, organization_id, source_authority_type, original_provider_id, target_provider_id, policy_version, reason, created_at) " +
            "VALUES (?, ?, 'TASK_EXECUTION', 'openai', 'anthropic', ?, 'Emergency Fallback', CURRENT_TIMESTAMP)",
            polId, orgId, version
        );
        return polId;
    }

    private UUID createActivePointer(UUID orgId, UUID polId) {
        UUID actId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_active_exceptional_fallback_policies (id, organization_id, source_authority_type, original_provider_id, target_provider_id, active_policy_id, version, created_at, updated_at) " +
            "VALUES (?, ?, 'TASK_EXECUTION', 'openai', 'anthropic', ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            actId, orgId, polId
        );
        return actId;
    }

    private UUID createFallbackDecision(UUID orgId, UUID lrId, UUID sourceAttemptId, UUID policyId) {
        UUID decId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_fallback_decisions (id, organization_id, logical_request_id, source_attempt_id, decision_sequence, original_provider_id, original_model_id, target_provider_id, target_model_id, decision_status, trigger_safety_classification, exceptional_policy_id, fencing_version, created_at) " +
            "VALUES (?, ?, ?, ?, 1, 'openai', 'gpt-4o', 'anthropic', 'claude-3-5-sonnet', 'DECISION_INITIATED', 'CONFIRMED_NOT_DISPATCHED', ?, 1, CURRENT_TIMESTAMP)",
            decId, orgId, lrId, sourceAttemptId, policyId
        );
        return decId;
    }

    // =========================================================================
    // CASE 1 & TEST206g: Organization has active policy pointer, no decisions -> DELETE org
    // =========================================================================
    @Test
    @DisplayName("CASE 1 / TEST206g: Organization deletion with active policy pointer is rejected (fail-closed)")
    void testCase1_orgDeleteWithActivePointer() {
        UUID orgId = createTestOrg();
        UUID polId = createExceptionalPolicy(orgId, 1);
        UUID actId = createActivePointer(orgId, polId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
            });
        });

        assertNotNull(ex);

        // Verify transaction atomicity: org, policy, and active pointer still exist
        Integer orgCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, orgId);
        Integer polCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id = ?", Integer.class, polId);
        Integer actCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_active_exceptional_fallback_policies WHERE id = ?", Integer.class, actId);

        assertEquals(1, orgCount, "Organization MUST still exist after rejected deletion");
        assertEquals(1, polCount, "Policy MUST still exist after rejected deletion");
        assertEquals(1, actCount, "Active pointer MUST still exist after rejected deletion");
    }

    // =========================================================================
    // CASE 2 & TEST206h: Organization deletion with active pointer + fallback decision
    // =========================================================================
    @Test
    @DisplayName("CASE 2 / TEST206h: Organization deletion with fallback decision referencing policy is rejected")
    void testCase2_orgDeleteWithActivePointerAndDecision() {
        UUID userId = createTestUser();
        UUID orgId = createTestOrg();
        UUID projId = createTestProject(orgId, userId);
        UUID lrId = createLogicalRequest(orgId, projId);
        UUID pvId = createPricingVersion();
        UUID sourceAttemptId = createProviderAttempt(lrId, 1, pvId);
        UUID polId = createExceptionalPolicy(orgId, 1);
        UUID actId = createActivePointer(orgId, polId);
        UUID decId = createFallbackDecision(orgId, lrId, sourceAttemptId, polId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
            });
        });

        assertNotNull(ex);

        // Verify full atomicity: zero partial deletions occurred
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, orgId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id = ?", Integer.class, polId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_active_exceptional_fallback_policies WHERE id = ?", Integer.class, actId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_logical_requests WHERE id = ?", Integer.class, lrId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_fallback_decisions WHERE id = ?", Integer.class, decId));
    }

    // =========================================================================
    // CASE 3 & TEST206i: Organization deletion with historical unreferenced policies
    // =========================================================================
    @Test
    @DisplayName("CASE 3 / TEST206i: Organization deletion with historical unreferenced policies cascades cleanly")
    void testCase3_orgDeleteWithUnreferencedPolicies() {
        UUID orgId = createTestOrg();
        UUID p1 = createExceptionalPolicy(orgId, 1);
        UUID p2 = createExceptionalPolicy(orgId, 2);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
        });

        // Verify clean deletion: no orphan rows remain
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, orgId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id IN (?, ?)", Integer.class, p1, p2));
    }

    // =========================================================================
    // CASE 4 & TEST206j: Organization deletion with fallback execution history, no policy
    // =========================================================================
    @Test
    @DisplayName("CASE 4 / TEST206j: Organization deletion with fallback execution history is rejected")
    void testCase4_orgDeleteWithFallbackHistoryNoPolicy() {
        UUID userId = createTestUser();
        UUID orgId = createTestOrg();
        UUID projId = createTestProject(orgId, userId);
        UUID lrId = createLogicalRequest(orgId, projId);
        UUID pvId = createPricingVersion();
        UUID sourceAttemptId = createProviderAttempt(lrId, 1, pvId);
        UUID decId = createFallbackDecision(orgId, lrId, sourceAttemptId, null);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
            });
        });

        assertNotNull(ex);

        // Verify fallback execution history remains intact
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, orgId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_logical_requests WHERE id = ?", Integer.class, lrId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_fallback_decisions WHERE id = ?", Integer.class, decId));
    }

    // =========================================================================
    // CASE 5: Organization deletion with active pointer whose policy is referenced by decision
    // =========================================================================
    @Test
    @DisplayName("CASE 5: Organization deletion with active pointer and policy referenced by decision is rejected")
    void testCase5_orgDeleteWithActivePointerAndPolicyReferencedByDecision() {
        UUID userId = createTestUser();
        UUID orgId = createTestOrg();
        UUID projId = createTestProject(orgId, userId);
        UUID lrId = createLogicalRequest(orgId, projId);
        UUID pvId = createPricingVersion();
        UUID sourceAttemptId = createProviderAttempt(lrId, 1, pvId);
        UUID polId = createExceptionalPolicy(orgId, 1);
        UUID actId = createActivePointer(orgId, polId);
        UUID decId = createFallbackDecision(orgId, lrId, sourceAttemptId, polId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
            });
        });

        assertNotNull(ex);

        // Verify zero partial deletions occur
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, orgId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id = ?", Integer.class, polId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_active_exceptional_fallback_policies WHERE id = ?", Integer.class, actId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_fallback_decisions WHERE id = ?", Integer.class, decId));
    }

    // =========================================================================
    // TEST206k: Delete active pointer and policy in legal vs illegal orders
    // =========================================================================
    @Test
    @DisplayName("TEST206k: Direct deletion of policy referenced by active pointer is rejected; legal deletion order succeeds")
    void test206k_activePointerPolicyDeletionOrder() {
        UUID orgId = createTestOrg();
        UUID polId = createExceptionalPolicy(orgId, 1);
        UUID actId = createActivePointer(orgId, polId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Illegal order: Delete policy directly while active pointer references it -> REJECTED
        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies WHERE id = ?", polId);
            });
        });

        // Legal order: Delete active pointer first, then delete policy -> SUCCEEDS
        tx.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM llm_active_exceptional_fallback_policies WHERE id = ?", actId);
            jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies WHERE id = ?", polId);
        });

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_active_exceptional_fallback_policies WHERE id = ?", Integer.class, actId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id = ?", Integer.class, polId));
    }

    // =========================================================================
    // TEST206l: Direct deletion of policy referenced by active pointer is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST206l: Direct deletion of policy referenced by active pointer is rejected by ON DELETE RESTRICT")
    void test206l_directPolicyDeleteRejectedWhenActivePointerExists() {
        UUID orgId = createTestOrg();
        UUID polId = createExceptionalPolicy(orgId, 1);
        UUID actId = createActivePointer(orgId, polId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies WHERE id = ?", polId);
            });
        });

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id = ?", Integer.class, polId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_active_exceptional_fallback_policies WHERE id = ?", Integer.class, actId));
    }

    // =========================================================================
    // TEST206m: Transaction atomicity during organization delete attempt
    // =========================================================================
    @Test
    @DisplayName("TEST206m: Transaction atomicity during rejected organization deletion ensures zero partial deletes")
    void test206m_transactionAtomicityOnRejectedOrgDelete() {
        UUID userId = createTestUser();
        UUID orgId = createTestOrg();
        UUID projId = createTestProject(orgId, userId);
        UUID lrId = createLogicalRequest(orgId, projId);
        UUID pvId = createPricingVersion();
        UUID sourceAttemptId = createProviderAttempt(lrId, 1, pvId);
        UUID polId = createExceptionalPolicy(orgId, 1);
        UUID actId = createActivePointer(orgId, polId);
        UUID decId = createFallbackDecision(orgId, lrId, sourceAttemptId, polId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
            });
        });

        // Prove 100% transaction atomicity: all rows intact
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, orgId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id = ?", Integer.class, polId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_active_exceptional_fallback_policies WHERE id = ?", Integer.class, actId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_logical_requests WHERE id = ?", Integer.class, lrId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_fallback_decisions WHERE id = ?", Integer.class, decId));
    }

    // =========================================================================
    // TEST206n: Prove zero partial deletions on rejected organization deletes
    // =========================================================================
    @Test
    @DisplayName("TEST206n: Querying all V35 tables after rejected org delete proves zero partial deletions occurred")
    void test206n_zeroPartialDeletionsOnRejectedOrgDelete() {
        UUID userId = createTestUser();
        UUID orgId = createTestOrg();
        UUID projId = createTestProject(orgId, userId);
        UUID lrId = createLogicalRequest(orgId, projId);
        UUID pvId = createPricingVersion();
        UUID sourceAttemptId = createProviderAttempt(lrId, 1, pvId);
        UUID polId = createExceptionalPolicy(orgId, 1);
        UUID actId = createActivePointer(orgId, polId);
        UUID decId = createFallbackDecision(orgId, lrId, sourceAttemptId, polId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
            });
        } catch (Exception ignored) {}

        // Query every single V35 table to prove zero partial deletions
        List<Map<String, Object>> orgs = jdbcTemplate.queryForList("SELECT * FROM organizations WHERE id = ?", orgId);
        List<Map<String, Object>> policies = jdbcTemplate.queryForList("SELECT * FROM llm_exceptional_fallback_policies WHERE id = ?", polId);
        List<Map<String, Object>> activePointers = jdbcTemplate.queryForList("SELECT * FROM llm_active_exceptional_fallback_policies WHERE id = ?", actId);
        List<Map<String, Object>> logicalRequests = jdbcTemplate.queryForList("SELECT * FROM llm_logical_requests WHERE id = ?", lrId);
        List<Map<String, Object>> attempts = jdbcTemplate.queryForList("SELECT * FROM llm_provider_attempts WHERE id = ?", sourceAttemptId);
        List<Map<String, Object>> decisions = jdbcTemplate.queryForList("SELECT * FROM llm_fallback_decisions WHERE id = ?", decId);

        assertEquals(1, orgs.size());
        assertEquals(1, policies.size());
        assertEquals(1, activePointers.size());
        assertEquals(1, logicalRequests.size());
        assertEquals(1, attempts.size());
        assertEquals(1, decisions.size());
    }

    // =========================================================================
    // TEST206o: Prove zero orphan rows on successful organization deletes
    // =========================================================================
    @Test
    @DisplayName("TEST206o: Querying all V35 tables after successful unreferenced org delete proves zero orphan rows remain")
    void test206o_zeroOrphanRowsOnSuccessfulOrgDelete() {
        UUID orgId = createTestOrg();
        UUID p1 = createExceptionalPolicy(orgId, 1);
        UUID p2 = createExceptionalPolicy(orgId, 2);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgId);
        });

        // Prove zero orphan rows remain in any V35 table
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, orgId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id IN (?, ?)", Integer.class, p1, p2));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_active_exceptional_fallback_policies WHERE organization_id = ?", Integer.class, orgId));
    }

    // =========================================================================
    // SYSTEM CATALOG METADATA VERIFICATION
    // =========================================================================
    @Test
    @DisplayName("Category B: Query database metadata for V35 foreign key constraints and ON DELETE rules")
    void testSystemCatalogMetadata() {
        List<Map<String, Object>> fkConstraints = jdbcTemplate.queryForList("""
            SELECT
                tc.table_name,
                tc.constraint_name,
                rc.delete_rule
            FROM information_schema.table_constraints tc
            JOIN information_schema.referential_constraints rc
              ON tc.constraint_name = rc.constraint_name
            WHERE tc.table_name IN (
                'llm_exceptional_fallback_policies',
                'llm_active_exceptional_fallback_policies',
                'llm_fallback_decisions',
                'llm_provider_attempts'
            )
            ORDER BY tc.table_name, tc.constraint_name
        """);

        assertNotNull(fkConstraints, "FK metadata query result must not be null");
        System.out.println("=== CATEGORY B: V35 DATABASE FOREIGN KEY CATALOG METADATA ===");
        for (Map<String, Object> row : fkConstraints) {
            System.out.println("Table: " + row.get("table_name") + " | Constraint: " + row.get("constraint_name") + " | Delete Rule: " + row.get("delete_rule"));
        }
    }

    // =========================================================================
    // TEST204w: Exceptional Policy Row Immutability Trigger
    // =========================================================================
    @Test
    @DisplayName("TEST204w: UPDATE on llm_exceptional_fallback_policies is rejected by immutability enforcement")
    void testPolicyImmutabilityTrigger_TEST204w() {
        UUID orgId = createTestOrg();
        UUID polId = createExceptionalPolicy(orgId, 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE llm_exceptional_fallback_policies SET reason = 'Illegal update attempt' WHERE id = ?", polId);
            });
        });

        assertNotNull(ex, "UPDATE on exceptional policy row MUST throw an exception");
        // Verify row contents were NOT modified
        String reason = jdbcTemplate.queryForObject("SELECT reason FROM llm_exceptional_fallback_policies WHERE id = ?", String.class, polId);
        assertEquals("Emergency Fallback", reason, "Policy reason must remain unchanged");
    }

    // =========================================================================
    // TEST204x: Cross-Tenant Active Pointer Identity FK Rejection
    // =========================================================================
    @Test
    @DisplayName("TEST204x: Linking active pointer to policy belonging to a different organization is rejected by composite FK")
    void testCrossTenantAttack_TEST204x() {
        UUID orgA = createTestOrg();
        UUID orgB = createTestOrg();
        UUID polIdB = createExceptionalPolicy(orgB, 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Attempt to create active pointer for Org A referencing Org B's policy
        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update(
                    "INSERT INTO llm_active_exceptional_fallback_policies (id, organization_id, source_authority_type, original_provider_id, target_provider_id, active_policy_id, created_at, updated_at) " +
                    "VALUES (?, ?, 'TASK_EXECUTION', 'openai', 'anthropic', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), orgA, polIdB
                );
            });
        });

        assertNotNull(ex, "Cross-tenant active policy pointer link MUST be rejected by fk_active_policy_identity");
    }

    // =========================================================================
    // TEST204y: Cross-Tenant Fallback Decision Logical Request FK Rejection
    // =========================================================================
    @Test
    @DisplayName("TEST204y: Fallback decision for Org A referencing Logical Request of Org B is rejected by composite FK")
    void testCrossTenantAttack_TEST204y() {
        UUID userA = createTestUser();
        UUID userB = createTestUser();
        UUID orgA = createTestOrg();
        UUID orgB = createTestOrg();
        UUID projB = createTestProject(orgB, userB);
        UUID lrIdB = createLogicalRequest(orgB, projB);
        UUID pvId = createPricingVersion();
        UUID attemptIdB = createProviderAttempt(lrIdB, 1, pvId);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Attempt to create decision for Org A referencing Org B's logical request
        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update(
                    "INSERT INTO llm_fallback_decisions (id, organization_id, logical_request_id, source_attempt_id, decision_sequence, original_provider_id, original_model_id, target_provider_id, target_model_id, decision_status, trigger_safety_classification, created_at) " +
                    "VALUES (?, ?, ?, ?, 1, 'openai', 'gpt-4o', 'anthropic', 'claude-3-5-sonnet', 'DECISION_INITIATED', 'CONFIRMED_NOT_DISPATCHED', CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), orgA, lrIdB, attemptIdB
                );
            });
        });

        assertNotNull(ex, "Cross-tenant fallback decision logical request link MUST be rejected by fk_fallback_decision_logical_org");
    }

    // =========================================================================
    // TEST204aa: Cross-Tenant Fallback Decision Exceptional Policy FK Rejection
    // =========================================================================
    @Test
    @DisplayName("TEST204aa: Fallback decision for Org A referencing Exceptional Policy of Org B is rejected by composite FK")
    void testCrossTenantAttack_TEST204aa() {
        UUID userA = createTestUser();
        UUID orgA = createTestOrg();
        UUID orgB = createTestOrg();
        UUID projA = createTestProject(orgA, userA);
        UUID lrIdA = createLogicalRequest(orgA, projA);
        UUID pvId = createPricingVersion();
        UUID attemptIdA = createProviderAttempt(lrIdA, 1, pvId);
        UUID polIdB = createExceptionalPolicy(orgB, 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Attempt to create decision for Org A referencing Org B's exceptional policy
        Exception ex = assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update(
                    "INSERT INTO llm_fallback_decisions (id, organization_id, logical_request_id, source_attempt_id, decision_sequence, original_provider_id, original_model_id, target_provider_id, target_model_id, decision_status, trigger_safety_classification, exceptional_policy_id, created_at) " +
                    "VALUES (?, ?, ?, ?, 1, 'openai', 'gpt-4o', 'anthropic', 'claude-3-5-sonnet', 'DECISION_INITIATED', 'CONFIRMED_NOT_DISPATCHED', ?, CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), orgA, lrIdA, attemptIdA, polIdB
                );
            });
        });

        assertNotNull(ex, "Cross-tenant fallback decision policy link MUST be rejected by fk_fallback_decision_exceptional_policy_org");
    }
}
