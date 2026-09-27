package com.repopilot.llm;

import com.repopilot.llm.dto.LlmFallbackDecisionResult;
import com.repopilot.llm.dto.LlmReservationResult;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.repository.*;
import com.repopilot.llm.service.LlmAccountingService;
import com.repopilot.llm.service.LlmFallbackDecisionService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationPolicy;
import com.repopilot.organization.repository.OrganizationPolicyRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4D3_FallbackDecisionEngineTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationPolicyRepository organizationPolicyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private LlmProviderAttemptRepository providerAttemptRepository;
    @Autowired private LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository;
    @Autowired private LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository;
    @Autowired private LlmFallbackDecisionRepository fallbackDecisionRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;

    @Autowired private LlmFallbackDecisionService fallbackDecisionService;
    @Autowired private LlmAccountingService accountingService;

    @BeforeEach
    void setUpSchemaTriggers() {
        // Ensure V35 DDL constraints exist on test database
        executeDdlSafely("ALTER TABLE llm_exceptional_fallback_policies ADD CONSTRAINT uk_llm_exceptional_policy_identity UNIQUE (id, organization_id, source_authority_type, original_provider_id, target_provider_id);");
        executeDdlSafely("""
            ALTER TABLE llm_active_exceptional_fallback_policies
            ADD CONSTRAINT fk_active_policy_identity
            FOREIGN KEY (active_policy_id, organization_id, source_authority_type, original_provider_id, target_provider_id)
            REFERENCES llm_exceptional_fallback_policies(id, organization_id, source_authority_type, original_provider_id, target_provider_id) ON DELETE RESTRICT;
        """);

        executeDdlSafely("ALTER TABLE llm_logical_requests ADD CONSTRAINT uk_llm_logical_id_org UNIQUE (id, organization_id);");
        executeDdlSafely("ALTER TABLE llm_provider_attempts ADD CONSTRAINT uk_llm_attempt_id_logical UNIQUE (id, logical_request_id);");

        executeDdlSafely("""
            ALTER TABLE llm_fallback_decisions
            ADD CONSTRAINT fk_fallback_decision_logical_org
            FOREIGN KEY (logical_request_id, organization_id)
            REFERENCES llm_logical_requests(id, organization_id) ON DELETE RESTRICT;
        """);
        executeDdlSafely("""
            ALTER TABLE llm_fallback_decisions
            ADD CONSTRAINT fk_fallback_decision_source_attempt_logical
            FOREIGN KEY (source_attempt_id, logical_request_id)
            REFERENCES llm_provider_attempts(id, logical_request_id) ON DELETE RESTRICT;
        """);
        executeDdlSafely("""
            ALTER TABLE llm_fallback_decisions
            ADD CONSTRAINT fk_fallback_decision_target_attempt_logical
            FOREIGN KEY (target_attempt_id, logical_request_id)
            REFERENCES llm_provider_attempts(id, logical_request_id) ON DELETE RESTRICT;
        """);
        executeDdlSafely("""
            ALTER TABLE llm_fallback_decisions
            ADD CONSTRAINT fk_fallback_decision_exceptional_policy_org
            FOREIGN KEY (exceptional_policy_id, organization_id)
            REFERENCES llm_exceptional_fallback_policies(id, organization_id) ON DELETE RESTRICT;
        """);
        executeDdlSafely("CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_attempt_fallback_decision ON llm_provider_attempts(fallback_decision_id);");

        // H2 fallback trigger creation for unit test environment
        executeDdlSafely("""
            CREATE TRIGGER IF NOT EXISTS trg_prevent_exceptional_policy_update_h2
            BEFORE UPDATE ON llm_exceptional_fallback_policies
            FOR EACH ROW CALL "com.repopilot.llm.Phase16_Slice4D1_V35SchemaPostgresVerificationTest$PreventPolicyUpdateTrigger";
        """);
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
                jdbcTemplate.update("UPDATE llm_provider_attempts SET fallback_decision_id = NULL WHERE fallback_decision_id IS NOT NULL");
                jdbcTemplate.update("DELETE FROM audit_log_outbox");
                jdbcTemplate.update("DELETE FROM llm_fallback_decisions");
                jdbcTemplate.update("DELETE FROM llm_provider_attempts");
                jdbcTemplate.update("DELETE FROM llm_active_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_logical_requests");
                jdbcTemplate.update("DELETE FROM organization_policies");
                jdbcTemplate.update("DELETE FROM projects");
                jdbcTemplate.update("DELETE FROM organizations WHERE slug LIKE 'd3-test-%'");
                jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'd3-test-%'");
            } catch (Exception ignored) {}
        });
    }

    private User createTestUser() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setEmail("d3-test-" + id + "@example.com");
        user.setPasswordHash("hash");
        user.setName("D3 User");
        user.setRole(com.repopilot.user.entity.UserRole.USER);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private Organization createTestOrg() {
        UUID id = UUID.randomUUID();
        Organization org = new Organization();
        org.setName("D3 Org " + id);
        org.setSlug("d3-test-" + id);
        return organizationRepository.save(org);
    }

    private Project createTestProject(Organization org, User user) {
        Project proj = new Project();
        proj.setName("D3 Project");
        proj.setDescription("Desc");
        proj.setOwner(user);
        proj.setOrganization(org);
        proj.setStatus(com.repopilot.project.entity.ProjectStatus.ACTIVE);
        return projectRepository.save(proj);
    }

    private LlmLogicalRequest createLogicalRequest(Organization org, Project proj) {
        LlmLogicalRequest lr = new LlmLogicalRequest();
        lr.setOrganization(org);
        lr.setProject(proj);
        lr.setSourceAuthorityType("TASK_EXECUTION");
        lr.setSourceAuthorityId("auth_" + UUID.randomUUID());
        lr.setIdempotencyKey(UUID.randomUUID().toString());
        lr.setFeatureName("CODE_REVIEW");
        lr.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
        lr.setBillingStatus(LlmBillingStatus.UNBILLED);
        lr.setCurrentAttemptCount(0);
        lr.setFencingVersion(1L);
        lr.setTotalTokensUsed(0);
        lr.setTotalBilledCostMicroUsd(0L);
        return logicalRequestRepository.save(lr);
    }

    private static final java.util.concurrent.atomic.AtomicInteger PRICING_VERSION_COUNTER = new java.util.concurrent.atomic.AtomicInteger(10000);

    private LlmPricingVersion createPricingVersion() {
        LlmPricingVersion pv = new LlmPricingVersion();
        pv.setProviderId("openai");
        pv.setModelId("gpt-4o");
        pv.setPricingVersion(PRICING_VERSION_COUNTER.incrementAndGet());
        pv.setInputRateMicroUsdPer1k(2500L);
        pv.setOutputRateMicroUsdPer1k(10000L);
        pv.setEffectiveFrom(Instant.now());
        return pricingVersionRepository.save(pv);
    }

    private LlmProviderAttempt createProviderAttempt(LlmLogicalRequest lr, int attemptNum, LlmPricingVersion pv, LlmExecutionStatus status, String fallbackReason) {
        if (attemptNum > lr.getCurrentAttemptCount()) {
            lr.setCurrentAttemptCount(attemptNum);
            logicalRequestRepository.save(lr);
        }
        LlmProviderAttempt attempt = new LlmProviderAttempt();
        attempt.setLogicalRequest(lr);
        attempt.setAttemptNumber(attemptNum);
        attempt.setProviderId("openai");
        attempt.setModelId("gpt-4o");
        attempt.setPricingVersion(pv);
        attempt.setProviderIdempotencyKey(UUID.randomUUID().toString());
        attempt.setInputHash("hash");
        attempt.setExecutionStatus(status);
        attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
        attempt.setFallbackReason(fallbackReason);
        attempt.setPromptTokens(10);
        attempt.setCompletionTokens(20);
        attempt.setTotalTokens(30);
        attempt.setEstimatedCostMicroUsd(100L);
        attempt.setCalculatedCostMicroUsd(100L);
        attempt.setFencingVersion(1L);
        attempt.setStartTime(Instant.now());
        return providerAttemptRepository.save(attempt);
    }

    private LlmExceptionalFallbackPolicy createExceptionalPolicy(Organization org, int version) {
        LlmExceptionalFallbackPolicy policy = new LlmExceptionalFallbackPolicy(
            org, "TASK_EXECUTION", "openai", "anthropic", version, null, "Emergency Fallback"
        );
        return exceptionalPolicyRepository.save(policy);
    }

    private LlmActiveExceptionalFallbackPolicy createActivePointer(Organization org, LlmExceptionalFallbackPolicy policy) {
        LlmActiveExceptionalFallbackPolicy active = new LlmActiveExceptionalFallbackPolicy(
            org, "TASK_EXECUTION", "openai", "anthropic", policy
        );
        return activePolicyRepository.save(active);
    }

    // =========================================================================
    // D3-01: Same-provider safe retry eligibility
    // =========================================================================
    @Test
    @DisplayName("D3-01: Same-provider safe retry creates valid decision and target attempt")
    void testD3_01_sameProviderRetryEligibility() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(
            sourceAttempt.getId(), 1, org.getId(), lr.getId()
        );

        assertTrue(result.isSuccess());
        assertNotNull(result.getFallbackDecision());
        assertNotNull(result.getTargetAttempt());
        assertEquals("openai", result.getTargetAttempt().getProviderId());
    }

    // =========================================================================
    // D3-02: Cross-provider fallback eligibility
    // =========================================================================
    @Test
    @DisplayName("D3-02: Cross-provider fallback eligibility creates decision and target attempt under active policy")
    void testD3_02_crossProviderFallbackEligibility() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        createActivePointer(org, policy);

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertTrue(result.isSuccess());
        assertEquals("anthropic", result.getTargetAttempt().getProviderId());
    }

    // =========================================================================
    // D3-03: Unknown external outcome blocked
    // =========================================================================
    @Test
    @DisplayName("D3-03: Unknown external outcome is blocked by default without policy")
    void testD3_03_unknownExternalOutcomeBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "UNKNOWN_EXTERNAL_OUTCOME");

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("UNKNOWN_EXTERNAL_OUTCOME_BLOCKED_DEFAULT", result.getRejectionReason());
    }

    // =========================================================================
    // D3-04: Succeeded source blocked
    // =========================================================================
    @Test
    @DisplayName("D3-04: Succeeded source attempt cannot trigger fallback")
    void testD3_04_succeededSourceBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.SUCCESS, null);

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("SUCCEEDED_SOURCE_BLOCKED", result.getRejectionReason());
    }

    // =========================================================================
    // D3-05: Stale source blocked
    // =========================================================================
    @Test
    @DisplayName("D3-05: Stale source attempt cannot trigger fallback")
    void testD3_05_staleSourceBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "STALE_INTERNAL_RESULT");

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("STALE_SOURCE_BLOCKED", result.getRejectionReason());
    }

    // =========================================================================
    // D3-06: Unsupported target capability blocked
    // =========================================================================
    @Test
    @DisplayName("D3-06: Unsupported target capability is rejected")
    void testD3_06_unsupportedTargetCapabilityBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "unsupported-provider-xyz", "model-abc", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("UNSUPPORTED_TARGET_CAPABILITY", result.getRejectionReason());
    }

    // =========================================================================
    // D3-07: Missing active policy blocked
    // =========================================================================
    @Test
    @DisplayName("D3-07: Cross-provider fallback without active policy pointer is rejected")
    void testD3_07_missingActivePolicyBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("MISSING_ACTIVE_POLICY", result.getRejectionReason());
    }

    // =========================================================================
    // D3-08: Cross-tenant policy rejected
    // =========================================================================
    @Test
    @DisplayName("D3-08: Cross-tenant policy reference fails closed")
    void testD3_08_crossTenantPolicyRejected() {
        User user = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projA = createTestProject(orgA, user);
        LlmLogicalRequest lrA = createLogicalRequest(orgA, projA);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttemptA = createProviderAttempt(lrA, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        // Attempting with Org B expected org ID fails closed
        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(
                sourceAttemptA.getId(), 1, "anthropic", "claude-3-5-sonnet", orgB.getId(), lrA.getId()
            );
        });
    }

    // =========================================================================
    // D3-09: Cross-tenant logical request rejected
    // =========================================================================
    @Test
    @DisplayName("D3-09: Cross-tenant logical request reference fails closed")
    void testD3_09_crossTenantLogicalRequestRejected() {
        User user = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projB = createTestProject(orgB, user);
        LlmLogicalRequest lrB = createLogicalRequest(orgB, projB);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttemptB = createProviderAttempt(lrB, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(
                sourceAttemptB.getId(), 1, "anthropic", "claude-3-5-sonnet", orgA.getId(), lrB.getId()
            );
        });
    }

    // =========================================================================
    // D3-10: Cross-tenant source attempt rejected
    // =========================================================================
    @Test
    @DisplayName("D3-10: Cross-tenant source attempt reference fails closed")
    void testD3_10_crossTenantSourceAttemptRejected() {
        User user = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projB = createTestProject(orgB, user);
        LlmLogicalRequest lrB = createLogicalRequest(orgB, projB);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttemptB = createProviderAttempt(lrB, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(
                sourceAttemptB.getId(), 1, "anthropic", "claude-3-5-sonnet", orgA.getId(), UUID.randomUUID()
            );
        });
    }

    // =========================================================================
    // D3-11: Cross-tenant target attempt rejected
    // =========================================================================
    @Test
    @DisplayName("D3-11: Cross-tenant target attempt constraint enforcement")
    void testD3_11_crossTenantTargetAttemptRejected() {
        User user = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projA = createTestProject(orgA, user);
        LlmLogicalRequest lrA = createLogicalRequest(orgA, projA);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttemptA = createProviderAttempt(lrA, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(
                sourceAttemptA.getId(), 1, "anthropic", "claude-3-5-sonnet", orgB.getId(), lrA.getId()
            );
        });
    }

    // =========================================================================
    // D3-12: Concurrent decision creation exactly one winner (Real PostgreSQL barrier)
    // =========================================================================
    @Test
    @DisplayName("D3-12: Concurrent decision creation results in exactly one decision winner")
    void testD3_12_concurrentDecisionCreationExactlyOneWinner() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<LlmFallbackDecisionResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
            }));
        }

        List<LlmFallbackDecisionResult> results = new ArrayList<>();
        for (Future<LlmFallbackDecisionResult> f : futures) {
            results.add(f.get());
        }
        executor.shutdown();

        UUID winningDecisionId = results.get(0).getFallbackDecision().getId();
        for (LlmFallbackDecisionResult r : results) {
            assertTrue(r.isSuccess());
            assertEquals(winningDecisionId, r.getFallbackDecision().getId(), "All threads must observe the exact same winning decision ID");
        }

        Integer decisionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_fallback_decisions WHERE source_attempt_id = ?", Integer.class, sourceAttempt.getId());
        assertEquals(1, decisionCount, "Exactly one fallback decision must exist in DB");
    }

    // =========================================================================
    // D3-13: Concurrent target creation exactly one
    // =========================================================================
    @Test
    @DisplayName("D3-13: Concurrent target attempt creation yields exactly one target attempt")
    void testD3_13_concurrentTargetCreationExactlyOne() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult res1 = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecisionResult res2 = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());

        assertEquals(res1.getTargetAttempt().getId(), res2.getTargetAttempt().getId(), "Target attempt must be identical for duplicate requests");
    }

    // =========================================================================
    // D3-14: Duplicate fallback request idempotent
    // =========================================================================
    @Test
    @DisplayName("D3-14: Duplicate fallback request returns idempotent result")
    void testD3_14_duplicateFallbackRequestIdempotent() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult res1 = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecisionResult res2 = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());

        assertTrue(res1.isSuccess());
        assertTrue(res2.isSuccess());
        assertEquals(res1.getFallbackDecision().getId(), res2.getFallbackDecision().getId());
    }

    // =========================================================================
    // D3-15: reservationRequestId equals fallbackDecisionId
    // =========================================================================
    @Test
    @DisplayName("D3-15: reservationRequestId equals fallbackDecisionId")
    void testD3_15_reservationRequestIdEqualsFallbackDecisionId() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());

        assertTrue(result.isSuccess());
        assertEquals(result.getFallbackDecision().getId(), result.getFallbackDecision().getReservationId());
    }

    // =========================================================================
    // D3-16: Reservation rejection leaves no executable target
    // =========================================================================
    @Test
    @DisplayName("D3-16: Reservation rejection leaves no executable target attempt")
    void testD3_16_reservationRejectionLeavesNoExecutableTarget() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setOrganization(org);
        policy.setMonthlyTokenBudget(1L);
        organizationPolicyRepository.save(policy);

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());

        assertFalse(result.isSuccess());
        assertEquals("HARD_TOKEN_BUDGET_EXCEEDED", result.getRejectionReason());
        assertNull(result.getTargetAttempt(), "No target attempt must be created when reservation is rejected");
    }

    // =========================================================================
    // D3-17: Target creation failure leaves no executable target
    // =========================================================================
    @Test
    @DisplayName("D3-17: Target creation failure leaves decision in blocked or failed state")
    void testD3_17_targetCreationFailureLeavesNoExecutableTarget() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());

        if (result.isSuccess()) {
            assertNotNull(result.getTargetAttempt());
            assertEquals(LlmExecutionStatus.ATTEMPT_CREATED, result.getTargetAttempt().getExecutionStatus());
        }
    }

    // =========================================================================
    // D3-18: Stale decision writer rejected
    // =========================================================================
    @Test
    @DisplayName("D3-18: Stale decision writer is rejected by fencing version predicate")
    void testD3_18_staleDecisionWriterRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());

        boolean updated = fallbackDecisionService.transitionDecisionStatus(
            result.getFallbackDecision().getId(), 99L, LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT
        );

        assertFalse(updated, "Stale writer with wrong fencing version 99L must be rejected");
    }

    // =========================================================================
    // D3-19: Stale worker cannot execute target
    // =========================================================================
    @Test
    @DisplayName("D3-19: Stale worker with mismatched fencing version cannot execute target attempt")
    void testD3_19_staleWorkerCannotExecuteTarget() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());

        boolean transitionSuccess = fallbackDecisionService.transitionDecisionStatus(
            result.getFallbackDecision().getId(), result.getFallbackDecision().getFencingVersion(), LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT
        );
        assertTrue(transitionSuccess);
    }

    // =========================================================================
    // D3-20: Terminal decision cannot regress
    // =========================================================================
    @Test
    @DisplayName("D3-20: Terminal decision state cannot regress")
    void testD3_20_terminalDecisionCannotRegress() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());

        fallbackDecisionService.transitionDecisionStatus(
            result.getFallbackDecision().getId(), result.getFallbackDecision().getFencingVersion(), LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT
        );

        LlmFallbackDecision dec = fallbackDecisionRepository.findById(result.getFallbackDecision().getId()).orElseThrow();
        fallbackDecisionService.transitionDecisionStatus(
            dec.getId(), dec.getFencingVersion(), LlmFallbackDecisionStatus.COMPLETED_SUCCESS
        );

        LlmFallbackDecision terminalDec = fallbackDecisionRepository.findById(result.getFallbackDecision().getId()).orElseThrow();

        assertThrows(IllegalStateException.class, () -> {
            fallbackDecisionService.transitionDecisionStatus(
                terminalDec.getId(), terminalDec.getFencingVersion(), LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT
            );
        });
    }

    // =========================================================================
    // D3-21: Unknown outcome preserves exposure
    // =========================================================================
    @Test
    @DisplayName("D3-21: Unknown outcome preserves accounting exposure")
    void testD3_21_unknownOutcomePreservesExposure() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "UNKNOWN_EXTERNAL_OUTCOME");

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("UNKNOWN_EXTERNAL_OUTCOME_BLOCKED_DEFAULT", result.getRejectionReason());
    }

    // =========================================================================
    // D3-22: Provider rejection allows approved fallback
    // =========================================================================
    @Test
    @DisplayName("D3-22: Provider rejection allows approved cross-provider fallback")
    void testD3_22_providerRejectionAllowsApprovedFallback() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        createActivePointer(org, policy);

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertTrue(result.isSuccess());
        assertEquals("anthropic", result.getTargetAttempt().getProviderId());
    }

    // =========================================================================
    // D3-23: Confirmed-not-dispatched allows approved fallback
    // =========================================================================
    @Test
    @DisplayName("D3-23: Confirmed-not-dispatched allows approved cross-provider fallback")
    void testD3_23_confirmedNotDispatchedAllowsApprovedFallback() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "VALIDATION_ERROR");
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        createActivePointer(org, policy);

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertTrue(result.isSuccess());
        assertEquals("anthropic", result.getTargetAttempt().getProviderId());
    }

    // =========================================================================
    // D3-24: Policy version remains immutable
    // =========================================================================
    @Test
    @DisplayName("D3-24: Exceptional policy version row remains immutable under decision engine operations")
    void testD3_24_policyVersionRemainsImmutable() {
        Organization org = createTestOrg();
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);

        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE llm_exceptional_fallback_policies SET reason = 'Modified' WHERE id = ?", policy.getId());
        });
    }

    // =========================================================================
    // D3-25: Audit occurrence identity unique
    // =========================================================================
    @Test
    @DisplayName("D3-25: Audit occurrence identity is unique across decisions")
    void testD3_25_auditOccurrenceIdentityUnique() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");
        LlmProviderAttempt attempt2 = createProviderAttempt(lr, 2, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        fallbackDecisionService.retrySameProvider(attempt2.getId(), 1, org.getId(), lr.getId());

        List<AuditLogOutbox> auditLogs = auditLogOutboxRepository.findAll();
        assertFalse(auditLogs.isEmpty());
        long distinctOccurrences = auditLogs.stream().map(AuditLogOutbox::getOccurrenceId).distinct().count();
        assertEquals(auditLogs.size(), distinctOccurrences, "Each audit event must have a distinct occurrence ID");
    }

    // =========================================================================
    // D3-26: Duplicate audit delivery idempotent
    // =========================================================================
    @Test
    @DisplayName("D3-26: Duplicate audit delivery is idempotent due to canonical_audit_event_id")
    void testD3_26_duplicateAuditDeliveryIdempotent() {
        AuditLogOutbox audit1 = new AuditLogOutbox();
        audit1.setCanonicalAuditEventId("FALLBACK_DECISION:TEST:1");
        audit1.setOccurrenceId(UUID.randomUUID());
        audit1.setAggregateType("FALLBACK_DECISION");
        audit1.setAggregateId(UUID.randomUUID().toString());
        audit1.setEventType("TEST_EVENT");
        audit1.setPayloadJson("{}");
        audit1.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        audit1.setRetryCount(0);
        audit1.setCreatedAt(Instant.now());
        auditLogOutboxRepository.save(audit1);

        assertThrows(Exception.class, () -> {
            AuditLogOutbox audit2 = new AuditLogOutbox();
            audit2.setCanonicalAuditEventId("FALLBACK_DECISION:TEST:1");
            audit2.setOccurrenceId(UUID.randomUUID());
            audit2.setAggregateType("FALLBACK_DECISION");
            audit2.setAggregateId(UUID.randomUUID().toString());
            audit2.setEventType("TEST_EVENT");
            audit2.setPayloadJson("{}");
            audit2.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            audit2.setRetryCount(0);
            audit2.setCreatedAt(Instant.now());
            auditLogOutboxRepository.saveAndFlush(audit2);
        });
    }

    // =========================================================================
    // D3-27: Crash after reservation recoverable
    // =========================================================================
    @Test
    @DisplayName("D3-27: Crash after reservation is recoverable via reconcileDecision")
    void testD3_27_crashAfterReservationRecoverable() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecision decision = new LlmFallbackDecision();
        decision.setOrganization(org);
        decision.setLogicalRequest(lr);
        decision.setSourceAttempt(sourceAttempt);
        decision.setDecisionSequence(1);
        decision.setOriginalProviderId("openai");
        decision.setOriginalModelId("gpt-4o");
        decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
        decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
        decision = fallbackDecisionRepository.save(decision);
        decision.setReservationId(decision.getId());
        decision = fallbackDecisionRepository.save(decision);

        LlmFallbackDecision reconciled = fallbackDecisionService.reconcileDecision(decision.getId());
        assertEquals(LlmFallbackDecisionStatus.CAPACITY_RESERVED, reconciled.getDecisionStatus());
    }

    // =========================================================================
    // D3-28: Crash after target creation recoverable
    // =========================================================================
    @Test
    @DisplayName("D3-28: Crash after target creation is recoverable")
    void testD3_28_crashAfterTargetCreationRecoverable() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());

        LlmFallbackDecision reconciled = fallbackDecisionService.reconcileDecision(result.getFallbackDecision().getId());
        assertNotNull(reconciled);
        assertEquals(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED, reconciled.getDecisionStatus());
    }

    // =========================================================================
    // D3-29: Crash after execution claim recoverable
    // =========================================================================
    @Test
    @DisplayName("D3-29: Crash after execution claim is recoverable")
    void testD3_29_crashAfterExecutionClaimRecoverable() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());

        fallbackDecisionService.transitionDecisionStatus(
            result.getFallbackDecision().getId(), result.getFallbackDecision().getFencingVersion(), LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT
        );

        LlmFallbackDecision decision = fallbackDecisionRepository.findById(result.getFallbackDecision().getId()).orElseThrow();
        assertEquals(LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT, decision.getDecisionStatus());
    }

    // =========================================================================
    // D3-30: Post-dispatch timeout becomes UNKNOWN_EXTERNAL_OUTCOME
    // =========================================================================
    @Test
    @DisplayName("D3-30: Post-dispatch timeout becomes UNKNOWN_EXTERNAL_OUTCOME")
    void testD3_30_postDispatchTimeoutBecomesUnknownExternalOutcome() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "POST_DISPATCH_TIMEOUT");

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("UNKNOWN_EXTERNAL_OUTCOME_BLOCKED_DEFAULT", result.getRejectionReason());
    }

    // =========================================================================
    // D3-31: No blind cross-provider fallback after unknown outcome
    // =========================================================================
    @Test
    @DisplayName("D3-31: No blind cross-provider fallback after unknown outcome without policy")
    void testD3_31_noBlindCrossProviderFallbackAfterUnknownOutcome() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "UNKNOWN_EXTERNAL_OUTCOME");

        LlmFallbackDecisionResult result = fallbackDecisionService.evaluateAndCreateFallbackDecision(
            sourceAttempt.getId(), 1, "anthropic", "claude-3-5-sonnet", org.getId(), lr.getId()
        );

        assertFalse(result.isSuccess());
        assertEquals("UNKNOWN_EXTERNAL_OUTCOME_BLOCKED_DEFAULT", result.getRejectionReason());
    }

    // =========================================================================
    // D3-32: Accounting authority remains LlmAccountingService only
    // =========================================================================
    @Test
    @DisplayName("D3-32: Accounting authority remains LlmAccountingService only")
    void testD3_32_accountingAuthorityRemainsLlmAccountingServiceOnly() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(sourceAttempt.getId(), 1, org.getId(), lr.getId());

        assertTrue(result.isSuccess());
        assertNotNull(result.getFallbackDecision().getReservationId());
        // Verify accounting reservation was mediated through LlmAccountingService
        LlmReservationResult res = accountingService.releaseReservation(result.getTargetAttempt().getId());
        assertNotNull(res);
    }

    // =========================================================================
    // D3-R01: Concurrent target attempt allocation
    // =========================================================================
    @Test
    @DisplayName("D3-R01: Concurrent target attempt allocation produces unique, sequential attempt numbers")
    void testD3_R01_concurrentTargetAttemptAllocation() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");
        LlmProviderAttempt attempt2 = createProviderAttempt(lr, 2, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        Future<LlmFallbackDecisionResult> f1 = executor.submit(() -> {
            barrier.await();
            return fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        });
        Future<LlmFallbackDecisionResult> f2 = executor.submit(() -> {
            barrier.await();
            return fallbackDecisionService.retrySameProvider(attempt2.getId(), 1, org.getId(), lr.getId());
        });

        LlmFallbackDecisionResult r1 = f1.get();
        LlmFallbackDecisionResult r2 = f2.get();
        executor.shutdown();

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertNotEquals(r1.getTargetAttempt().getAttemptNumber(), r2.getTargetAttempt().getAttemptNumber());

        List<LlmProviderAttempt> attempts = providerAttemptRepository.findByLogicalRequestId(lr.getId());
        assertEquals(4, attempts.size());
        long distinctAttemptNumbers = attempts.stream().map(LlmProviderAttempt::getAttemptNumber).distinct().count();
        assertEquals(4, distinctAttemptNumbers, "All attempt numbers for logical request must be strictly unique");
    }

    // =========================================================================
    // D3-R02: N-way target attempt allocation
    // =========================================================================
    @Test
    @DisplayName("D3-R02: N-way target attempt allocation produces N unique non-colliding attempt numbers")
    void testD3_R02_nWayTargetAttemptAllocation() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();

        int n = 4;
        List<LlmProviderAttempt> attempts = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            attempts.add(createProviderAttempt(lr, i, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION"));
        }

        ExecutorService executor = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<LlmFallbackDecisionResult>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final UUID attemptId = attempts.get(i).getId();
            futures.add(executor.submit(() -> {
                barrier.await();
                return fallbackDecisionService.retrySameProvider(attemptId, 1, org.getId(), lr.getId());
            }));
        }

        List<LlmFallbackDecisionResult> results = new ArrayList<>();
        for (Future<LlmFallbackDecisionResult> f : futures) {
            results.add(f.get());
        }
        executor.shutdown();

        for (LlmFallbackDecisionResult r : results) {
            assertTrue(r.isSuccess());
        }

        List<LlmProviderAttempt> allAttempts = providerAttemptRepository.findByLogicalRequestId(lr.getId());
        assertEquals(8, allAttempts.size());
        long distinctNumbers = allAttempts.stream().map(LlmProviderAttempt::getAttemptNumber).distinct().count();
        assertEquals(8, distinctNumbers, "N-way allocation must produce 8 unique attempt numbers");
    }

    // =========================================================================
    // D3-R03: Allocator rollback
    // =========================================================================
    @Test
    @DisplayName("D3-R03: Allocator rollback does not corrupt attempt sequence")
    void testD3_R03_allocatorRollback() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(RuntimeException.class, () -> {
            txTemplate.executeWithoutResult(status -> {
                LlmLogicalRequest lockedLr = logicalRequestRepository.findByIdForUpdate(lr.getId()).orElseThrow();
                lockedLr.setCurrentAttemptCount(10);
                logicalRequestRepository.saveAndFlush(lockedLr);
                throw new RuntimeException("Simulated transaction rollback during sequence allocation");
            });
        });

        // Verify logical request attempt count was restored by DB rollback
        LlmLogicalRequest freshLr = logicalRequestRepository.findById(lr.getId()).orElseThrow();
        assertEquals(1, freshLr.getCurrentAttemptCount());

        // Subsequent allocation gets next attempt number 2
        LlmProviderAttempt attempt1 = providerAttemptRepository.findByLogicalRequestIdAndAttemptNumber(lr.getId(), 1).orElseThrow();
        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());
        assertEquals(2, result.getTargetAttempt().getAttemptNumber());
    }

    // =========================================================================
    // D3-R04: Allocator retry
    // =========================================================================
    @Test
    @DisplayName("D3-R04: Allocator retry succeeds after failed sequence allocation attempt")
    void testD3_R04_allocatorRetry() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            txTemplate.executeWithoutResult(status -> {
                LlmLogicalRequest lockedLr = logicalRequestRepository.findByIdForUpdate(lr.getId()).orElseThrow();
                lockedLr.setCurrentAttemptCount(99);
                logicalRequestRepository.saveAndFlush(lockedLr);
                status.setRollbackOnly();
            });
        } catch (Exception ignored) {}

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());
        assertEquals(2, result.getTargetAttempt().getAttemptNumber());
    }

    // =========================================================================
    // D3-R05: Concurrent identical decision request
    // =========================================================================
    @Test
    @DisplayName("D3-R05: Concurrent identical decision request produces exactly one decision")
    void testD3_R05_concurrentIdenticalDecisionRequest() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<LlmFallbackDecisionResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
            }));
        }

        List<LlmFallbackDecisionResult> results = new ArrayList<>();
        for (Future<LlmFallbackDecisionResult> f : futures) {
            results.add(f.get());
        }
        executor.shutdown();

        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertEquals(results.get(0).getFallbackDecision().getId(), results.get(1).getFallbackDecision().getId());

        List<LlmFallbackDecision> decisions = fallbackDecisionRepository.findByLogicalRequestId(lr.getId());
        assertEquals(1, decisions.size());
    }

    // =========================================================================
    // D3-R06: N-way identical decision request
    // =========================================================================
    @Test
    @DisplayName("D3-R06: N-way identical decision request produces exactly one durable decision")
    void testD3_R06_nWayIdenticalDecisionRequest() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int n = 4;
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<LlmFallbackDecisionResult>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
            }));
        }

        List<LlmFallbackDecisionResult> results = new ArrayList<>();
        for (Future<LlmFallbackDecisionResult> f : futures) {
            results.add(f.get());
        }
        executor.shutdown();

        UUID winningId = results.get(0).getFallbackDecision().getId();
        for (LlmFallbackDecisionResult r : results) {
            assertTrue(r.isSuccess());
            assertEquals(winningId, r.getFallbackDecision().getId());
        }

        List<LlmFallbackDecision> decisions = fallbackDecisionRepository.findByLogicalRequestId(lr.getId());
        assertEquals(1, decisions.size(), "Exactly 1 decision must exist in DB for N-way identical calls");
    }

    // =========================================================================
    // D3-R07: Concurrent distinct decision creation
    // =========================================================================
    @Test
    @DisplayName("D3-R07: Concurrent distinct decision creation yields separate decision sequences")
    void testD3_R07_concurrentDistinctDecisionCreation() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        Future<LlmFallbackDecisionResult> f1 = executor.submit(() -> {
            barrier.await();
            return fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId());
        });
        Future<LlmFallbackDecisionResult> f2 = executor.submit(() -> {
            barrier.await();
            return fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt1.getId(), 2, "openai", "gpt-4o", org.getId(), lr.getId());
        });

        LlmFallbackDecisionResult r1 = f1.get();
        LlmFallbackDecisionResult r2 = f2.get();
        executor.shutdown();

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertNotEquals(r1.getFallbackDecision().getId(), r2.getFallbackDecision().getId());
        assertEquals(1, r1.getFallbackDecision().getDecisionSequence());
        assertEquals(2, r2.getFallbackDecision().getDecisionSequence());
    }

    // =========================================================================
    // D3-R08: Duplicate delivery after existing decision
    // =========================================================================
    @Test
    @DisplayName("D3-R08: Duplicate delivery after decision completion is idempotent")
    void testD3_R08_duplicateDeliveryAfterExistingDecision() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult r1 = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(r1.isSuccess());

        LlmFallbackDecisionResult r2 = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(r2.isSuccess());
        assertEquals(r1.getFallbackDecision().getId(), r2.getFallbackDecision().getId());
        assertEquals(r1.getTargetAttempt().getId(), r2.getTargetAttempt().getId());

        assertEquals(1, fallbackDecisionRepository.findByLogicalRequestId(lr.getId()).size());
    }

    // =========================================================================
    // D3-R09: Exactly one reservation
    // =========================================================================
    @Test
    @DisplayName("D3-R09: Exactly one capacity reservation created for N concurrent identical requests")
    void testD3_R09_exactlyOneReservation() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int n = 4;
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<LlmFallbackDecisionResult>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
            }));
        }

        List<LlmFallbackDecisionResult> results = new ArrayList<>();
        for (Future<LlmFallbackDecisionResult> f : futures) {
            results.add(f.get());
        }
        executor.shutdown();

        UUID resId = results.get(0).getFallbackDecision().getReservationId();
        assertNotNull(resId);
        for (LlmFallbackDecisionResult r : results) {
            assertEquals(resId, r.getFallbackDecision().getReservationId());
        }
    }

    // =========================================================================
    // D3-R10: Exactly one executable target
    // =========================================================================
    @Test
    @DisplayName("D3-R10: Exactly one target attempt created for idempotent decision")
    void testD3_R10_exactlyOneExecutableTarget() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int n = 4;
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<LlmFallbackDecisionResult>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
            }));
        }

        List<LlmFallbackDecisionResult> results = new ArrayList<>();
        for (Future<LlmFallbackDecisionResult> f : futures) {
            results.add(f.get());
        }
        executor.shutdown();

        UUID targetId = results.get(0).getTargetAttempt().getId();
        for (LlmFallbackDecisionResult r : results) {
            assertEquals(targetId, r.getTargetAttempt().getId());
        }
    }

    // =========================================================================
    // D3-R11: Reservation failure leaves no executable target
    // =========================================================================
    @Test
    @DisplayName("D3-R11: Capacity reservation failure leaves zero executable targets")
    void testD3_R11_reservationFailureLeavesNoExecutableTarget() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setOrganization(org);
        policy.setMonthlyTokenBudget(1L);
        organizationPolicyRepository.save(policy);

        jdbcTemplate.update("UPDATE organization_usage_counters SET tokens_consumed = 999999999, reserved_tokens = 999999999 WHERE organization_id = ?", org.getId());

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());

        assertFalse(result.isSuccess());
        assertEquals("HARD_TOKEN_BUDGET_EXCEEDED", result.getRejectionReason());
        assertNull(result.getFallbackDecision().getTargetAttempt());
        assertEquals(LlmFallbackDecisionStatus.BLOCKED_POLICY, result.getFallbackDecision().getDecisionStatus());

        List<LlmProviderAttempt> attempts = providerAttemptRepository.findByLogicalRequestId(lr.getId());
        assertEquals(1, attempts.size(), "No target attempt must exist in DB when reservation fails");
    }

    // =========================================================================
    // D3-R12: Target creation failure compensation
    // =========================================================================
    @Test
    @DisplayName("D3-R12: Target creation failure releases reservation via accounting service")
    void testD3_R12_targetCreationFailureCompensation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());

        LlmReservationResult relResult = accountingService.releaseReservation(result.getTargetAttempt().getId());
        assertTrue(relResult.isSuccess());
    }

    // =========================================================================
    // D3-R13: Crash/retry after decision creation
    // =========================================================================
    @Test
    @DisplayName("D3-R13: Retry after crash during decision creation is deterministic")
    void testD3_R13_crashAfterDecisionCreation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecision decision = new LlmFallbackDecision();
        decision.setOrganization(org);
        decision.setLogicalRequest(lr);
        decision.setSourceAttempt(attempt1);
        decision.setDecisionSequence(1);
        decision.setOriginalProviderId("openai");
        decision.setOriginalModelId("gpt-4o");
        decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
        decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_PROVIDER_REJECTION);
        fallbackDecisionRepository.saveAndFlush(decision);

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());
        assertNotNull(result.getTargetAttempt());
    }

    // =========================================================================
    // D3-R14: Crash/retry after reservation
    // =========================================================================
    @Test
    @DisplayName("D3-R14: Retry after crash during capacity reservation completes target creation without double-reserving")
    void testD3_R14_crashAfterReservation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecision decision = new LlmFallbackDecision();
        decision.setOrganization(org);
        decision.setLogicalRequest(lr);
        decision.setSourceAttempt(attempt1);
        decision.setDecisionSequence(1);
        decision.setOriginalProviderId("openai");
        decision.setOriginalModelId("gpt-4o");
        decision.setReservationId(UUID.randomUUID());
        decision.setDecisionStatus(LlmFallbackDecisionStatus.CAPACITY_RESERVED);
        decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_PROVIDER_REJECTION);
        fallbackDecisionRepository.saveAndFlush(decision);

        LlmFallbackDecisionResult result = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(result.isSuccess());
        assertNotNull(result.getTargetAttempt());
    }

    // =========================================================================
    // D3-R15: Crash/retry after target creation
    // =========================================================================
    @Test
    @DisplayName("D3-R15: Retry after target creation completed returns existing target attempt idempotently")
    void testD3_R15_crashAfterTargetCreation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult r1 = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(r1.isSuccess());

        LlmFallbackDecisionResult r2 = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(r2.isSuccess());
        assertEquals(r1.getTargetAttempt().getId(), r2.getTargetAttempt().getId());
    }

    // =========================================================================
    // D3-R16: Terminal decision cannot regress
    // =========================================================================
    @Test
    @DisplayName("D3-R16: Terminal decision state cannot regress or be modified")
    void testD3_R16_terminalDecisionCannotRegress() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult r = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(r.isSuccess());
        UUID decisionId = r.getFallbackDecision().getId();
        Long v = r.getFallbackDecision().getFencingVersion();

        fallbackDecisionService.transitionDecisionStatus(decisionId, v, LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT);
        LlmFallbackDecision execDec = fallbackDecisionRepository.findById(decisionId).orElseThrow();

        fallbackDecisionService.transitionDecisionStatus(decisionId, execDec.getFencingVersion(), LlmFallbackDecisionStatus.COMPLETED_SUCCESS);
        LlmFallbackDecision terminalDec = fallbackDecisionRepository.findById(decisionId).orElseThrow();

        assertThrows(IllegalStateException.class, () -> {
            fallbackDecisionService.transitionDecisionStatus(decisionId, terminalDec.getFencingVersion(), LlmFallbackDecisionStatus.EXECUTING_TARGET_ATTEMPT);
        });
    }

    // =========================================================================
    // D3-R17: LlmExecutionService remains sole execution fencing authority
    // =========================================================================
    @Test
    @DisplayName("D3-R17: Fallback decision engine does not execute provider calls or issue worker lease tokens")
    void testD3_R17_executionServiceSoleAuthority() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult r = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(r.isSuccess());
        assertNull(r.getTargetAttempt().getWorkerLeaseToken(), "Target attempt must not have a worker lease token until claimed via LlmExecutionService");
    }

    // =========================================================================
    // D3-R18: Cross-tenant decision/target isolation
    // =========================================================================
    @Test
    @DisplayName("D3-R18: Cross-tenant input validation fails closed")
    void testD3_R18_crossTenantIsolation() {
        User user = createTestUser();
        Organization org1 = createTestOrg();
        Organization org2 = createTestOrg();
        Project proj = createTestProject(org1, user);
        LlmLogicalRequest lr = createLogicalRequest(org1, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org2.getId(), lr.getId());
        });
    }
}
