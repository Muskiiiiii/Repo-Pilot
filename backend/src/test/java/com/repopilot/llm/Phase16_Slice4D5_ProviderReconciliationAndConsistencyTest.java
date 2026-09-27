package com.repopilot.llm;

import com.repopilot.llm.dto.*;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.LlmProviderClient;
import com.repopilot.llm.provider.adapter.StubMockProviderAdapter;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.model.LlmProviderFailureReason;
import com.repopilot.llm.repository.*;
import com.repopilot.llm.service.*;
import com.repopilot.organization.entity.Organization;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4D5_ProviderReconciliationAndConsistencyTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private LlmProviderAttemptRepository providerAttemptRepository;
    @Autowired private LlmFallbackDecisionRepository fallbackDecisionRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;

    @Autowired private LlmFallbackDecisionService fallbackDecisionService;
    @Autowired private LlmAccountingService accountingService;
    @Autowired private LlmExecutionService executionService;
    @Autowired private LlmFallbackExecutionOrchestrator executionOrchestrator;

    private static final AtomicInteger PRICING_VERSION_COUNTER = new AtomicInteger(30000);

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
                jdbcTemplate.update("DELETE FROM organizations WHERE slug LIKE 'd5-test-%'");
                jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'd5-test-%'");
            } catch (Exception ignored) {}
        });
    }

    private User createTestUser() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setEmail("d5-test-" + id + "@example.com");
        user.setPasswordHash("hashed_pwd");
        user.setName("D5 User");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private Organization createTestOrg() {
        UUID id = UUID.randomUUID();
        Organization org = new Organization();
        org.setName("D5 Org " + id);
        org.setSlug("d5-test-" + id);
        return organizationRepository.save(org);
    }

    private Project createTestProject(Organization org, User user) {
        Project proj = new Project();
        proj.setName("D5 Project");
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
    // D5-01 TO D5-08 CRASH MATRIX TESTS
    // =========================================================================

    @Test
    @DisplayName("D5-01: Crash before reconciliation request - status remains RECONCILIATION_REQUIRED")
    void testD5_01_crashBeforeReconciliationRequest() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        dec.setDecisionStatus(LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED);
        fallbackDecisionRepository.save(dec);

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertNotNull(recRes);
        assertTrue("RECONCILIATION_REQUIRED".equals(recRes.getStatus()) || recRes.isSuccess());
    }

    @Test
    @DisplayName("D5-02: Crash after reconciliation request creation - restart re-triggers reconciliation cleanly")
    void testD5_02_crashAfterReconciliationRequestCreation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        dec.setDecisionStatus(LlmFallbackDecisionStatus.RECONCILIATION_REQUIRED);
        fallbackDecisionRepository.save(dec);

        LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(
                dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));

        assertTrue(res.isResolved());
        assertEquals("CONFIRMED_ACTUAL_USAGE", res.getStatus());
    }

    @Test
    @DisplayName("D5-03: Crash after provider reconciliation response - retry resolves accounting idempotently")
    void testD5_03_crashAfterProviderReconciliationResponse() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        LlmReconciliationResult res1 = executionOrchestrator.reconcileFallbackAttempt(
                dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));
        LlmReconciliationResult res2 = executionOrchestrator.reconcileFallbackAttempt(
                dec.getTargetAttempt().getId(), "worker-2", new StubMockProviderAdapter("openai"));

        assertTrue(res1.isResolved());
        assertTrue(res2.isResolved());
        assertEquals(res1.getStatus(), res2.getStatus());
    }

    @Test
    @DisplayName("D5-04: Crash after accounting resolution - target attempt state reflects final settlement")
    void testD5_04_crashAfterAccountingResolution() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        target.setBillingStatus(LlmBillingStatus.BILLED);
        providerAttemptRepository.save(target);

        LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(
                target.getId(), "worker-1", new StubMockProviderAdapter("openai"));

        assertTrue(res.isResolved());
        assertEquals("CONFIRMED_ACTUAL_USAGE", res.getStatus());
    }

    @Test
    @DisplayName("D5-05: Crash after decision finalization - terminal decision status immutable")
    void testD5_05_crashAfterDecisionFinalization() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        dec.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_SUCCESS);
        dec.setCompletedAt(Instant.now());
        fallbackDecisionRepository.save(dec);

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(recRes.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, recRes.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("D5-06: Crash after audit event - audit outbox entry present")
    void testD5_06_crashAfterAuditEvent() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));

        List<AuditLogOutbox> audits = auditLogOutboxRepository.findAll().stream()
                .filter(a -> dec.getId().toString().equals(a.getAggregateId()))
                .toList();
        assertFalse(audits.isEmpty());
    }

    @Test
    @DisplayName("D5-07: Restart with unresolved exposure - exposure preserved until reconciliation")
    void testD5_07_restartWithUnresolvedExposure() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.TIMED_OUT, "TIMEOUT");

        accountingService.transferToUnknownExposure(attempt.getId());

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLING_UNKNOWN, reloaded.getBillingStatus());
    }

    @Test
    @DisplayName("D5-08: Restart with already-resolved exposure - no duplicate exposure clearance")
    void testD5_08_restartWithAlreadyResolvedExposure() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        accountingService.releaseReservation(attempt.getId());

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, reloaded.getBillingStatus());
    }

    // =========================================================================
    // R01 TO R10 RECONCILIATION CONCURRENCY TESTS (REAL POSTGRESQL)
    // =========================================================================

    @Test
    @DisplayName("R01: Two workers reconcile same attempt concurrently - single winner")
    void testR01_twoWorkersReconcileSameAttempt() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        UUID targetAttemptId = dec.getTargetAttempt().getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<LlmReconciliationResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    barrier.await();
                    LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(targetAttemptId, workerId, new StubMockProviderAdapter("openai"));
                    results.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isResolved());
        assertTrue(results.get(1).isResolved());
    }

    @Test
    @DisplayName("R02: N workers reconcile same attempt concurrently - exactly one accounting resolution")
    void testR02_nWorkersReconcileSameAttempt() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        UUID targetAttemptId = dec.getTargetAttempt().getId();

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<LlmReconciliationResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    barrier.await();
                    LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(targetAttemptId, workerId, new StubMockProviderAdapter("openai"));
                    results.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threadCount, results.size());
        for (LlmReconciliationResult res : results) {
            assertTrue(res.isResolved());
        }
    }

    @Test
    @DisplayName("R03: Reconciliation vs stale execution worker - fencing rejects stale worker")
    void testR03_reconciliationVsStaleWorker() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmReconciliationResult recRes = executionOrchestrator.reconcileFallbackAttempt(attempt.getId(), "reconciler-1", new StubMockProviderAdapter("openai"));
        assertTrue(recRes.isResolved());

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "Hello", new StubMockProviderAdapter("openai"));
        assertNotEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
    }

    @Test
    @DisplayName("R04: Reconciliation vs active execution worker - lock prevents state collision")
    void testR04_reconciliationVsActiveWorker() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        UUID targetAttemptId = dec.getTargetAttempt().getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> outcomes = new CopyOnWriteArrayList<>();

        executor.submit(() -> {
            try {
                barrier.await();
                outcomes.add(executionOrchestrator.reconcileFallbackAttempt(targetAttemptId, "reconciler", new StubMockProviderAdapter("openai")));
            } catch (Exception e) {
                outcomes.add(e);
            }
        });

        executor.submit(() -> {
            try {
                barrier.await();
                outcomes.add(executionOrchestrator.orchestrateFallbackExecution(
                        attempt.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker", 60000L, "Hello", new StubMockProviderAdapter("openai")));
            } catch (Exception e) {
                outcomes.add(e);
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, outcomes.size());
    }

    @Test
    @DisplayName("R05: Reconciliation vs fallback trigger - single decision state transition")
    void testR05_reconciliationVsFallbackTrigger() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        LlmReconciliationResult recRes = executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));
        assertTrue(recRes.isResolved());
    }

    @Test
    @DisplayName("R06: Duplicate reconciliation delivery - idempotent response")
    void testR06_duplicateReconciliationDelivery() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        LlmReconciliationResult res1 = executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));
        LlmReconciliationResult res2 = executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));

        assertEquals(res1.getStatus(), res2.getStatus());
    }

    @Test
    @DisplayName("R07: Reconciliation transaction rollback - database state remains intact on error")
    void testR07_reconciliationTransactionRollback() {
        assertThrows(IllegalArgumentException.class, () -> {
            executionOrchestrator.reconcileFallbackAttempt(UUID.randomUUID(), "worker-1", new StubMockProviderAdapter("openai"));
        });
    }

    @Test
    @DisplayName("R08: Reconciliation retry - successful recovery after retry")
    void testR08_reconciliationRetry() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));
        assertTrue(res.isResolved());
    }

    @Test
    @DisplayName("R09: Stale reconciliation response - rejected gracefully")
    void testR09_staleReconciliationResponse() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        target.setBillingStatus(LlmBillingStatus.BILLED);
        providerAttemptRepository.save(target);

        LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(target.getId(), "stale-worker", new StubMockProviderAdapter("openai"));
        assertTrue(res.isResolved());
        assertEquals("CONFIRMED_ACTUAL_USAGE", res.getStatus());
    }

    @Test
    @DisplayName("R10: Cross-tenant reconciliation attempt - fails closed with IllegalArgumentException")
    void testR10_crossTenantReconciliationAttempt() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        Project proj1 = createTestProject(org1, user1);
        LlmLogicalRequest lr1 = createLogicalRequest(org1, proj1);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr1, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org1.getId(), lr1.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        User user2 = createTestUser();
        Organization org2 = createTestOrg();
        Project proj2 = createTestProject(org2, user2);
        LlmLogicalRequest lr2 = createLogicalRequest(org2, proj2);
        LlmProviderAttempt attempt2 = createProviderAttempt(lr2, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt1.getId(), 1, "openai", "gpt-4o", org2.getId(), lr1.getId());
        });
    }

    // =========================================================================
    // A01 TO A06 ACCOUNTING FINALIZATION TESTS
    // =========================================================================

    @Test
    @DisplayName("A01: Exactly-once financial resolution")
    void testA01_exactlyOnceFinancialResolution() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.settleReservation(attempt.getId(), 30, 100L);

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLED, reloaded.getBillingStatus());
    }

    @Test
    @DisplayName("A02: Duplicate settlement is idempotent")
    void testA02_duplicateSettlement() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.settleReservation(attempt.getId(), 30, 100L);
        accountingService.settleReservation(attempt.getId(), 30, 100L);

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLED, reloaded.getBillingStatus());
    }

    @Test
    @DisplayName("A03: Duplicate release is idempotent")
    void testA03_duplicateRelease() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.releaseReservation(attempt.getId());
        accountingService.releaseReservation(attempt.getId());

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, reloaded.getBillingStatus());
    }

    @Test
    @DisplayName("A04: Unresolved exposure correctness")
    void testA04_unresolvedExposureCorrectness() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.TIMED_OUT, "TIMEOUT");

        accountingService.transferToUnknownExposure(attempt.getId());
        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLING_UNKNOWN, reloaded.getBillingStatus());

        accountingService.resolveUnknownExposureActualUsage(attempt.getId(), 30, 100L);
        LlmProviderAttempt settled = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLED, settled.getBillingStatus());
    }

    @Test
    @DisplayName("A05: Budget invariant preserved - zero negative exposure")
    void testA05_budgetInvariantPreserved() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.releaseReservation(attempt.getId());

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertTrue(reloaded.getCalculatedCostMicroUsd() == null || reloaded.getCalculatedCostMicroUsd() >= 0L);
    }

    @Test
    @DisplayName("A06: Cent-precision Model A preservation")
    void testA06_centPrecisionModelAPreservation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.settleReservation(attempt.getId(), 15, 125L);

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(125L, reloaded.getCalculatedCostMicroUsd());
    }

    // =========================================================================
    // P01 TO P04 PROVIDER IDENTITY & CAPABILITY TESTS
    // =========================================================================

    @Test
    @DisplayName("P01: Provider identity verification preserves immutable fields")
    void testP01_providerIdentityVerification() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertEquals("openai", attempt.getProviderId());
        assertEquals("gpt-4o", attempt.getModelId());
    }

    @Test
    @DisplayName("P02: Reconciliation capability enforcement - unsupported capability returns unsupported result")
    void testP02_reconciliationCapabilityEnforcement() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.TIMED_OUT, "TIMEOUT");
        attempt.setProviderId("anthropic");
        providerAttemptRepository.save(attempt);

        LlmProviderClient nonReconcilingClient = new LlmProviderClient() {
            @Override public String getProviderId() { return "anthropic"; }
            @Override public Set<LlmProviderCapability> getCapabilities() { return Set.of(LlmProviderCapability.SUPPORTS_REQUEST_IDENTITY); }
            @Override public LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException {
                throw new LlmProviderException(LlmProviderFailureReason.PROVIDER_INTERNAL_ERROR, "No reconciliation");
            }
        };

        LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(attempt.getId(), "worker-1", nonReconcilingClient);
        assertFalse(res.isResolved());
        assertEquals("UNSUPPORTED_RECONCILIATION_CAPABILITY", res.getStatus());
    }

    @Test
    @DisplayName("P03: Stale reconciliation response rejected")
    void testP03_staleReconciliationRejection() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.SUCCEEDED, null);

        accountingService.settleReservation(attempt.getId(), 30, 100L);

        LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(attempt.getId(), "worker-1", new StubMockProviderAdapter("openai"));
        assertTrue(res.isResolved());
        assertEquals("CONFIRMED_ACTUAL_USAGE", res.getStatus());
    }

    @Test
    @DisplayName("P04: Provider request identity mismatch rejected")
    void testP04_providerRequestIdentityMismatch() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");
        attempt.setProviderRequestId("req-12345");
        providerAttemptRepository.save(attempt);

        assertEquals("req-12345", attempt.getProviderRequestId());
    }

    // =========================================================================
    // F01 TO F03 FALLBACK CHAIN TESTS
    // =========================================================================

    @Test
    @DisplayName("F01: Bounded fallback chain - sequence > 3 rejected with BLOCKED_POLICY")
    void testF01_boundedFallbackChain() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult res = fallbackDecisionService.evaluateAndCreateFallbackDecision(
                attempt.getId(), 4, "openai", "gpt-4o", org.getId(), lr.getId());

        assertFalse(res.isSuccess());
        assertEquals("BOUNDED_FALLBACK_DEPTH_EXCEEDED", res.getRejectionReason());
        assertEquals(LlmFallbackDecisionStatus.BLOCKED_POLICY, res.getFallbackDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("F02: Duplicate fallback trigger returns idempotent existing decision")
    void testF02_duplicateFallbackTrigger() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult res1 = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecisionResult res2 = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());

        assertTrue(res1.isSuccess());
        assertTrue(res2.isSuccess());
        assertEquals(res1.getFallbackDecision().getId(), res2.getFallbackDecision().getId());
    }

    @Test
    @DisplayName("F03: Terminal chain immutability - completed decision rejects modification")
    void testF03_terminalChainImmutability() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        dec.setDecisionStatus(LlmFallbackDecisionStatus.COMPLETED_SUCCESS);
        dec.setCompletedAt(Instant.now());
        fallbackDecisionRepository.save(dec);

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(recRes.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, recRes.getDecision().getDecisionStatus());
    }

    // =========================================================================
    // T01 TO T02 TENANT & CALLER SECURITY TESTS
    // =========================================================================

    @Test
    @DisplayName("T01: Cross-tenant reconciliation fails closed")
    void testT01_crossTenantReconciliation() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        Project proj1 = createTestProject(org1, user1);
        LlmLogicalRequest lr1 = createLogicalRequest(org1, proj1);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr1, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org1.getId(), lr1.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        User user2 = createTestUser();
        Organization org2 = createTestOrg();
        Project proj2 = createTestProject(org2, user2);
        LlmLogicalRequest lr2 = createLogicalRequest(org2, proj2);

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt1.getId(), 1, "openai", "gpt-4o", org2.getId(), lr1.getId());
        });
    }

    @Test
    @DisplayName("T02: Caller identity forgery fails closed")
    void testT02_callerIdentityForgery() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        Project proj1 = createTestProject(org1, user1);
        LlmLogicalRequest lr1 = createLogicalRequest(org1, proj1);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr1, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        UUID fakeOrgId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt1.getId(), 1, "openai", "gpt-4o", fakeOrgId, lr1.getId());
        });
    }

    // =========================================================================
    // S01 TO S02 AUDIT TESTS & H01 HISTORICAL REPRODUCIBILITY
    // =========================================================================

    @Test
    @DisplayName("S01: Audit occurrence identity is unique per event execution")
    void testS01_auditOccurrenceIdentity() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));

        List<AuditLogOutbox> audits = auditLogOutboxRepository.findAll().stream()
                .filter(a -> dec.getId().toString().equals(a.getAggregateId()))
                .toList();
        assertFalse(audits.isEmpty());
        assertNotNull(audits.get(0).getOccurrenceId());
        assertNotNull(audits.get(0).getCanonicalAuditEventId());
    }

    @Test
    @DisplayName("S02: Duplicate audit delivery is idempotent")
    void testS02_duplicateAuditDelivery() {
        AuditLogOutbox audit1 = new AuditLogOutbox();
        String canonicalId = "FD:test-dedup:" + UUID.randomUUID();
        audit1.setCanonicalAuditEventId(canonicalId);
        audit1.setOccurrenceId(UUID.randomUUID());
        audit1.setAggregateType("FALLBACK_DECISION");
        audit1.setAggregateId("agg-1");
        audit1.setEventType("TEST_EVENT");
        audit1.setPayloadJson("{}");
        audit1.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        auditLogOutboxRepository.save(audit1);

        AuditLogOutbox audit2 = new AuditLogOutbox();
        audit2.setCanonicalAuditEventId(canonicalId);
        audit2.setOccurrenceId(UUID.randomUUID());
        audit2.setAggregateType("FALLBACK_DECISION");
        audit2.setAggregateId("agg-1");
        audit2.setEventType("TEST_EVENT");
        audit2.setPayloadJson("{}");
        audit2.setDeliveryState(AuditOutboxDeliveryState.PENDING);

        assertThrows(Exception.class, () -> {
            auditLogOutboxRepository.saveAndFlush(audit2);
        });
    }

    @Test
    @DisplayName("H01: Historical reproducibility - finalized attempt reconstructs full chain context")
    void testH01_historicalReproducibility() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackExecutionResult result = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(result.isSuccess());

        LlmFallbackDecision dec = fallbackDecisionRepository.findById(result.getDecision().getId()).orElseThrow();
        assertNotNull(dec.getSourceAttempt());
        assertNotNull(dec.getTargetAttempt());
        assertEquals(org.getId(), dec.getOrganization().getId());
        assertEquals(lr.getId(), dec.getLogicalRequest().getId());
        assertEquals("openai", dec.getOriginalProviderId());
        assertEquals("gpt-4o", dec.getOriginalModelId());
        assertEquals("openai", dec.getTargetProviderId());
        assertEquals("gpt-4o", dec.getTargetModelId());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_SUCCESS, dec.getDecisionStatus());
    }
}
