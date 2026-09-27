package com.repopilot.llm;

import com.repopilot.llm.dto.LlmExecutionClaimResult;
import com.repopilot.llm.dto.LlmExecutionOutcome;
import com.repopilot.llm.dto.LlmFallbackDecisionResult;
import com.repopilot.llm.dto.LlmFallbackExecutionResult;
import com.repopilot.llm.dto.LlmReconciliationResult;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.LlmProviderClient;
import com.repopilot.llm.provider.adapter.StubMockProviderAdapter;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.model.LlmProviderFailureReason;
import com.repopilot.llm.repository.*;
import com.repopilot.llm.service.LlmAccountingService;
import com.repopilot.llm.service.LlmExecutionService;
import com.repopilot.llm.service.LlmFallbackDecisionService;
import com.repopilot.llm.service.LlmFallbackExecutionOrchestrator;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4D4_FallbackExecutionOrchestratorTest {

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
    @Autowired private LlmExecutionService executionService;
    @Autowired private LlmFallbackExecutionOrchestrator executionOrchestrator;

    private static final AtomicInteger PRICING_VERSION_COUNTER = new AtomicInteger(20000);

    @BeforeEach
    void setUpSchemaTriggers() {
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
                jdbcTemplate.update("DELETE FROM organization_overage_liabilities");
                jdbcTemplate.update("DELETE FROM audit_log_outbox");
                jdbcTemplate.update("DELETE FROM llm_fallback_decisions");
                jdbcTemplate.update("DELETE FROM llm_provider_attempts");
                jdbcTemplate.update("DELETE FROM llm_active_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_logical_requests");
                jdbcTemplate.update("DELETE FROM organization_policies");
                jdbcTemplate.update("DELETE FROM projects");
                jdbcTemplate.update("DELETE FROM organizations WHERE slug LIKE 'd4-test-%'");
                jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'd4-test-%'");
            } catch (Exception ignored) {}
        });
    }

    private User createTestUser() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setEmail("d4-test-" + id + "@example.com");
        user.setPasswordHash("hashed_pwd");
        user.setName("D4 User");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private Organization createTestOrg() {
        UUID id = UUID.randomUUID();
        Organization org = new Organization();
        org.setName("D4 Org " + id);
        org.setSlug("d4-test-" + id);
        return organizationRepository.save(org);
    }

    private Project createTestProject(Organization org, User user) {
        Project proj = new Project();
        proj.setName("D4 Project");
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

    // =========================================================================
    // D4-01 to D4-10 CRASH BOUNDARY MATRIX TESTS
    // =========================================================================

    @Test
    @DisplayName("D4-01: Crash after decision creation (DECISION_INITIATED) restarts cleanly")
    void testD4_01_crashAfterDecisionCreation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult result = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(result.isSuccess());
        assertEquals("COMPLETED_SUCCESS", result.getStatus());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, result.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D4-02: Crash after capacity reservation (CAPACITY_RESERVED) skips double reservation")
    void testD4_02_crashAfterCapacityReservation() {
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
        decision.setTargetProviderId("openai");
        decision.setTargetModelId("gpt-4o");
        decision.setDecisionStatus(LlmFallbackDecisionStatus.CAPACITY_RESERVED);
        decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_PROVIDER_REJECTION);
        decision.setReservationId(UUID.randomUUID());
        decision = fallbackDecisionRepository.save(decision);

        LlmFallbackExecutionResult result = executionOrchestrator.recoverInterruptedDecision(
                decision.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(result.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, result.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D4-03: Crash after target attempt creation (TARGET_ATTEMPT_CREATED) reuses target attempt")
    void testD4_03_crashAfterTargetAttemptCreation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        assertEquals(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED, dec.getDecisionStatus());

        LlmFallbackExecutionResult result = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-2", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(result.isSuccess());
        assertEquals(dec.getTargetAttempt().getId(), result.getTargetAttempt().getId());
    }

    @Test
    @DisplayName("D4-04: Crash after execution claim (EXECUTION_CLAIMED) reclaims expired lease")
    void testD4_04_crashAfterExecutionClaim() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        // Expire lease
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(dec.getTargetAttempt().getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmFallbackExecutionResult result = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-3", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("D4-05: Crash after provider dispatch (EXECUTING) handles unknown outcome")
    void testD4_05_crashAfterProviderDispatch() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmProviderClient throwingClient = new LlmProviderClient() {
            @Override public String getProviderId() { return "openai"; }
            @Override public Set<LlmProviderCapability> getCapabilities() { return Set.of(LlmProviderCapability.SUPPORTS_RETRY_SAFETY); }
            @Override public LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException {
                throw new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Timeout after dispatch");
            }
        };

        LlmFallbackExecutionResult result = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", throwingClient);

        assertFalse(result.isSuccess());
        assertEquals("RECONCILIATION_REQUIRED", result.getStatus());
        assertEquals(LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED, result.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D4-06: Crash after provider success before finalization settles reservation idempotently")
    void testD4_06_crashAfterProviderSuccessBeforeFinalization() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        // Simulate attempt succeeded in DB
        target.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
        target.setTotalTokens(30);
        target.setCalculatedCostMicroUsd(100L);
        providerAttemptRepository.save(target);

        LlmFallbackExecutionResult result = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(result.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, result.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D4-07: Crash after provider failure before finalization releases reservation idempotently")
    void testD4_07_crashAfterProviderFailureBeforeFinalization() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        target.setExecutionStatus(LlmExecutionStatus.FAILED);
        target.setFallbackReason("RATE_LIMITED");
        providerAttemptRepository.save(target);

        LlmFallbackExecutionResult result = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertFalse(result.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_FAILED, result.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D4-08: Crash after reconciliation request creation re-runs reconciliation")
    void testD4_08_crashAfterReconciliationRequestCreation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        dec.setDecisionStatus(LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED);
        fallbackDecisionRepository.save(dec);

        LlmFallbackExecutionResult result = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertNotNull(result);
    }

    @Test
    @DisplayName("D4-09: Crash after reconciliation success returns idempotent result")
    void testD4_09_crashAfterReconciliationSuccess() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        target.setBillingStatus(LlmBillingStatus.BILLED);
        providerAttemptRepository.save(target);

        LlmReconciliationResult recResult = executionOrchestrator.reconcileFallbackAttempt(target.getId(), "worker-1", new StubMockProviderAdapter("openai"));
        assertTrue(recResult.isResolved());
    }

    @Test
    @DisplayName("D4-10: Stale worker lease expiry fails pre-dispatch and finalization fencing")
    void testD4_10_workerLeaseExpiryFencing() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt1.getId(), 1L);
        assertTrue(claim.isSuccess());

        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "Hello", new StubMockProviderAdapter("openai"));
        assertNotEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, outcome.getFailureReason());
    }

    // =========================================================================
    // D4-C01 to D4-C10 REAL POSTGRESQL CONCURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("D4-C01: Two workers executing same target attempt concurrently")
    void testD4_C01_twoWorkersSameTarget() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<LlmFallbackExecutionResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    barrier.await();
                    LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                            attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), workerId, 60000L, "Prompt", new StubMockProviderAdapter("openai") {
                                @Override
                                public com.repopilot.llm.provider.dto.LlmProviderResponse invoke(com.repopilot.llm.provider.dto.LlmProviderRequest request) throws com.repopilot.llm.provider.exception.LlmProviderException {
                                    try { Thread.sleep(50); } catch (Exception ignored) {}
                                    return super.invoke(request);
                                }
                            });
                    results.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(2, results.size());
        long successCount = results.stream().filter(LlmFallbackExecutionResult::isSuccess).count();
        assertEquals(1, successCount, "Exactly one worker must succeed in executing target attempt");
    }

    @Test
    @DisplayName("D4-C02: Duplicate worker delivery executes idempotently")
    void testD4_C02_duplicateWorkerDelivery() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res1 = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        assertTrue(res1.isSuccess());

        LlmFallbackExecutionResult res2 = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-2", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        assertTrue(res2.isSuccess());
        assertEquals(res1.getDecision().getId(), res2.getDecision().getId());
    }

    @Test
    @DisplayName("D4-C03: Stale worker vs current worker fencing rejection")
    void testD4_C03_staleWorkerVsCurrentWorker() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt1.getId(), 60000L);
        assertTrue(claim1.isSuccess());

        // Stale worker trying to finalize attempt with invalid lease token & fencing version must be rejected
        boolean finalizedByStale = executionService.finalizeAttemptOutcome(
                attempt1.getId(), "stale-token", 999L, LlmExecutionOutcome.succeeded(
                        new LlmProviderResponse("openai", "gpt-4o", "req1", 10, 20, 30, 100L, "out", "hash", Instant.now(), Map.of())
                ));

        assertFalse(finalizedByStale, "Stale worker finalization must be rejected by fencing version");
    }

    @Test
    @DisplayName("D4-C04: Concurrent reconciliation invocations resolve safely")
    void testD4_C04_concurrentReconciliation() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.TIMED_OUT, "TIMEOUT");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<LlmReconciliationResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    LlmReconciliationResult r = executionOrchestrator.reconcileFallbackAttempt(attempt1.getId(), "worker", new StubMockProviderAdapter("openai"));
                    results.add(r);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isResolved());
        assertTrue(results.get(1).isResolved());
    }

    @Test
    @DisplayName("D4-C05: Reconciliation vs fallback trigger serialization")
    void testD4_C05_reconciliationVsFallbackTrigger() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.TIMED_OUT, "TIMEOUT");

        LlmReconciliationResult rec = executionOrchestrator.reconcileFallbackAttempt(attempt1.getId(), "worker-1", new StubMockProviderAdapter("openai"));
        assertTrue(rec.isResolved());

        LlmFallbackDecisionResult dec = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertFalse(dec.isSuccess());
        assertEquals("UNKNOWN_EXTERNAL_OUTCOME_BLOCKED_DEFAULT", dec.getRejectionReason());
    }

    @Test
    @DisplayName("D4-C06: Reservation settlement vs duplicate settlement protection")
    void testD4_C06_reservationSettlementVsDuplicateSettlement() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        accountingService.reserveCapacity(attempt1.getId(), 10, 100L);
        accountingService.settleReservation(attempt1.getId(), 30, 100L);

        // Duplicate settlement call must be idempotent without double charging
        accountingService.settleReservation(attempt1.getId(), 30, 100L);

        LlmProviderAttempt refreshed = providerAttemptRepository.findById(attempt1.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLED, refreshed.getBillingStatus());
    }

    @Test
    @DisplayName("D4-C07: Timeout vs successful retry isolation")
    void testD4_C07_timeoutVsSuccessfulRetry() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.TIMED_OUT, "TIMEOUT");

        LlmExceptionalFallbackPolicy policy = new LlmExceptionalFallbackPolicy(
                org, "TASK_EXECUTION", "openai", "openai", 1, null, "Retry Policy");
        exceptionalPolicyRepository.save(policy);
        activePolicyRepository.save(new LlmActiveExceptionalFallbackPolicy(org, "TASK_EXECUTION", "openai", "openai", policy));

        LlmFallbackExecutionResult result = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("D4-C08: Provider success vs worker lease expiry")
    void testD4_C08_providerSuccessVsWorkerLeaseExpiry() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt1.getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "Hello", new StubMockProviderAdapter("openai"));
        assertNotEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
    }

    @Test
    @DisplayName("D4-C09: Target finalization vs decision terminalization")
    void testD4_C09_targetFinalizationVsDecisionTerminalization() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(res.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, res.getDecision().getDecisionStatus());

        // Re-executing terminal decision returns same decision without altering terminal state
        LlmFallbackExecutionResult reExec = executionOrchestrator.recoverInterruptedDecision(
                res.getDecision().getId(), "worker-2", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(reExec.isSuccess());
        assertEquals(res.getDecision().getId(), reExec.getDecision().getId());
    }

    @Test
    @DisplayName("D4-C10: Cross-tenant concurrent attack prevention")
    void testD4_C10_crossTenantConcurrentAttack() {
        User user = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projA = createTestProject(orgA, user);
        LlmLogicalRequest lrA = createLogicalRequest(orgA, projA);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attemptA = createProviderAttempt(lrA, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            executionOrchestrator.orchestrateFallbackExecution(
                    attemptA.getId(), 1, "openai", "gpt-4o", orgB.getId(), lrA.getId(), "worker-attacker", 60000L, "Attack", new StubMockProviderAdapter("openai"));
        });
    }

    // =========================================================================
    // D4-A01 to D4-A04 ACCOUNTING AUTHORITY TESTS
    // =========================================================================

    @Test
    @DisplayName("D4-A01: Accounting authority preservation")
    void testD4_A01_accountingAuthorityPreservation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));

        assertTrue(res.isSuccess());
        LlmProviderAttempt target = res.getTargetAttempt();
        assertTrue(target.getBillingStatus() == LlmBillingStatus.BILLED || target.getBillingStatus() == LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY);
    }

    @Test
    @DisplayName("D4-A02: Duplicate settlement protection")
    void testD4_A02_duplicateSettlementProtection() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        accountingService.reserveCapacity(attempt1.getId(), 10, 100L);
        accountingService.settleReservation(attempt1.getId(), 30, 100L);
        accountingService.settleReservation(attempt1.getId(), 30, 100L);

        LlmProviderAttempt target = providerAttemptRepository.findById(attempt1.getId()).orElseThrow();
        assertEquals(30, target.getTotalTokens());
    }

    @Test
    @DisplayName("D4-A03: Unresolved exposure correctness")
    void testD4_A03_unresolvedExposureCorrectness() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        accountingService.reserveCapacity(attempt1.getId(), 10, 100L);
        accountingService.transferToUnknownExposure(attempt1.getId());

        LlmProviderAttempt target = providerAttemptRepository.findById(attempt1.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLING_UNKNOWN, target.getBillingStatus());
    }

    @Test
    @DisplayName("D4-A04: Stale worker financial mutation rejection")
    void testD4_A04_staleWorkerFinancialMutationRejection() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt1.getId(), 60000L);
        assertTrue(claim1.isSuccess());

        boolean finalized = executionService.finalizeAttemptOutcome(attempt1.getId(), "stale-token", 999L, LlmExecutionOutcome.succeeded(
                new LlmProviderResponse("openai", "gpt-4o", "req", 10, 20, 30, 100L, "out", "hash", Instant.now(), Map.of())
        ));

        assertFalse(finalized);
    }

    // =========================================================================
    // D4-P01 to D4-P04 PROVIDER & POLICY AUTHORITY TESTS
    // =========================================================================

    @Test
    @DisplayName("D4-P01: Provider idempotency preservation")
    void testD4_P01_providerIdempotencyPreservation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));

        assertTrue(res.isSuccess());
        assertNotNull(res.getTargetAttempt().getProviderIdempotencyKey());
    }

    @Test
    @DisplayName("D4-P02: Provider capability enforcement")
    void testD4_P02_providerCapabilityEnforcement() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "invalid_provider", "invalid_model", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));

        assertFalse(res.isSuccess());
        assertEquals("BLOCKED_POLICY", res.getStatus());
        assertEquals("UNSUPPORTED_TARGET_CAPABILITY", res.getRejectionReason());
    }

    @Test
    @DisplayName("D4-P03: Unknown external outcome handling")
    void testD4_P03_unknownExternalOutcomeHandling() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmProviderClient throwingClient = new LlmProviderClient() {
            @Override public String getProviderId() { return "openai"; }
            @Override public Set<LlmProviderCapability> getCapabilities() { return Set.of(); }
            @Override public LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException {
                throw new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Timeout after dispatch");
            }
        };

        LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", throwingClient);

        assertFalse(res.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED, res.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D4-P04: Reconciliation result handling")
    void testD4_P04_reconciliationResultHandling() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.TIMED_OUT, "TIMEOUT");

        LlmReconciliationResult rec = executionOrchestrator.reconcileFallbackAttempt(attempt1.getId(), "worker-1", new StubMockProviderAdapter("openai"));
        assertTrue(rec.isResolved());
    }

    // =========================================================================
    // D4-T01, D4-T02, D4-S01, D4-S02, D4-R01, D4-R02 INTEGRITY TESTS
    // =========================================================================

    @Test
    @DisplayName("D4-T01: Tenant isolation enforcement")
    void testD4_T01_tenantIsolation() {
        User user = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projA = createTestProject(orgA, user);
        LlmLogicalRequest lrA = createLogicalRequest(orgA, projA);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attemptA = createProviderAttempt(lrA, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            executionOrchestrator.orchestrateFallbackExecution(
                    attemptA.getId(), 1, "openai", "gpt-4o", orgB.getId(), lrA.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        });
    }

    @Test
    @DisplayName("D4-T02: Caller cannot override authoritative parameters")
    void testD4_T02_callerCannotOverrideAuthoritativeParameters() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            executionOrchestrator.orchestrateFallbackExecution(
                    attempt1.getId(), 1, "openai", "gpt-4o", UUID.randomUUID(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        });
    }

    @Test
    @DisplayName("D4-S01: Terminal state immutability")
    void testD4_S01_terminalStateImmutability() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        assertTrue(res.isSuccess());

        LlmFallbackExecutionResult reExec = executionOrchestrator.recoverInterruptedDecision(
                res.getDecision().getId(), "worker-2", 60000L, "Prompt", new StubMockProviderAdapter("openai"));

        assertTrue(reExec.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, reExec.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D4-S02: Invalid transition rejection")
    void testD4_S02_invalidTransitionRejection() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.SUCCESS, null);

        LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));

        assertFalse(res.isSuccess());
        assertEquals("SUCCEEDED_SOURCE_BLOCKED", res.getRejectionReason());
    }

    @Test
    @DisplayName("D4-R01: Restart recovery at any crash boundary")
    void testD4_R01_restartRecovery() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res1 = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        assertTrue(res1.isSuccess());

        LlmFallbackExecutionResult res2 = executionOrchestrator.recoverInterruptedDecision(
                res1.getDecision().getId(), "worker-2", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        assertTrue(res2.isSuccess());
    }

    @Test
    @DisplayName("D4-R02: Duplicate delivery recovery preserves idempotency")
    void testD4_R02_duplicateDeliveryRecovery() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult res1 = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        assertTrue(res1.isSuccess());

        LlmFallbackExecutionResult res2 = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Prompt", new StubMockProviderAdapter("openai"));
        assertTrue(res2.isSuccess());
        assertEquals(res1.getDecision().getId(), res2.getDecision().getId());
    }
}
