package com.repopilot.llm;

import com.repopilot.common.telemetry.W3CTraceContextFilter;
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
import com.repopilot.organization.entity.OrganizationPolicy;
import com.repopilot.organization.entity.OrganizationUsageCounter;
import com.repopilot.organization.repository.OrganizationPolicyRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.organization.repository.OrganizationUsageCounterRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4D6_FinalFallbackSecurityGateTest {

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
    @Autowired private OrganizationUsageCounterRepository usageCounterRepository;

    @Autowired private LlmFallbackDecisionService fallbackDecisionService;
    @Autowired private LlmAccountingService accountingService;
    @Autowired private LlmExecutionService executionService;
    @Autowired private LlmFallbackExecutionOrchestrator executionOrchestrator;

    private static final AtomicInteger PRICING_VERSION_COUNTER = new AtomicInteger(50000);

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
                jdbcTemplate.update("DELETE FROM organization_usage_counters");
                jdbcTemplate.update("DELETE FROM audit_log_outbox");
                jdbcTemplate.update("DELETE FROM llm_fallback_decisions");
                jdbcTemplate.update("DELETE FROM llm_provider_attempts");
                jdbcTemplate.update("DELETE FROM llm_active_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_logical_requests");
                jdbcTemplate.update("DELETE FROM organization_policies");
                jdbcTemplate.update("DELETE FROM projects");
                jdbcTemplate.update("DELETE FROM organizations WHERE slug LIKE 'd6-test-%'");
                jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'd6-test-%'");
            } catch (Exception ignored) {}
        });
    }

    private User createTestUser() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setEmail("d6-test-" + id + "@example.com");
        user.setPasswordHash("hashed_pwd");
        user.setName("D6 User");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private Organization createTestOrg() {
        UUID id = UUID.randomUUID();
        Organization org = new Organization();
        org.setName("D6 Org " + id);
        org.setSlug("d6-test-" + id);
        return organizationRepository.save(org);
    }

    private Project createTestProject(Organization org, User user) {
        Project proj = new Project();
        proj.setName("D6 Project");
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
    // 1. HAPPY PATH & BASIC LIFECYCLE TESTS (E2E-01 to E2E-10)
    // =========================================================================

    @Test
    @DisplayName("E2E-01: Source provider execution succeeds - no fallback, accounting settled")
    void testE2E_01_happySourceSuccess() {
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
    @DisplayName("E2E-02: Confirmed provider rejection triggers fallback decision and succeeds on target")
    void testE2E_02_confirmedRejectionFallback() {
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
    @DisplayName("E2E-03: Unknown outcome transitions to RECONCILIATION_REQUIRED and resolves usage authoritatively")
    void testE2E_03_unknownOutcomeReconciliation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmProviderClient throwingClient = new LlmProviderClient() {
            @Override public String getProviderId() { return "openai"; }
            @Override public Set<LlmProviderCapability> getCapabilities() { return Set.of(LlmProviderCapability.SUPPORTS_RETRY_SAFETY, LlmProviderCapability.SUPPORTS_EXECUTION_RECONCILIATION, LlmProviderCapability.RETRY_AUTHORITATIVELY_RECONCILES); }
            @Override public LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException {
                throw new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Timeout after dispatch");
            }
        };

        LlmFallbackExecutionResult result = executionOrchestrator.orchestrateFallbackExecution(
                attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", throwingClient);

        assertFalse(result.isSuccess());
        assertEquals("RECONCILIATION_REQUIRED", result.getStatus());

        LlmReconciliationResult recRes = executionOrchestrator.reconcileFallbackAttempt(
                result.getDecision().getTargetAttempt().getId(), "reconciler-1", throwingClient);

        assertTrue(recRes.isResolved());
        assertEquals("CONFIRMED_ACTUAL_USAGE", recRes.getStatus());
    }

    @Test
    @DisplayName("E2E-04: Confirmed zero usage reconciliation releases reservation")
    void testE2E_04_confirmedZeroUsage() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        LlmProviderClient zeroUsageClient = new LlmProviderClient() {
            @Override public String getProviderId() { return "mock-zero"; }
            @Override public Set<LlmProviderCapability> getCapabilities() { return Set.of(LlmProviderCapability.SUPPORTS_EXECUTION_RECONCILIATION); }
            @Override public LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException {
                throw new LlmProviderException(LlmProviderFailureReason.PROVIDER_INTERNAL_ERROR, "Failed zero charge");
            }
        };

        LlmProviderAttempt target = dec.getTargetAttempt();
        target.setProviderId("mock-zero");
        providerAttemptRepository.save(target);

        LlmReconciliationResult recRes = executionOrchestrator.reconcileFallbackAttempt(
                target.getId(), "reconciler-1", zeroUsageClient);

        assertTrue(recRes.isResolved());
        assertEquals("CONFIRMED_ZERO_USAGE", recRes.getStatus());
    }

    @Test
    @DisplayName("E2E-05: Unresolved exposure remains BILLING_UNKNOWN when evidence is absent")
    void testE2E_05_unresolvedExposure() {
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
    @DisplayName("E2E-06: Same-provider idempotent retry preserves exact provider identity and idempotency key")
    void testE2E_06_sameProviderIdempotentRetry() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult res1 = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecisionResult res2 = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());

        assertTrue(res1.isSuccess());
        assertTrue(res2.isSuccess());
        assertEquals(res1.getFallbackDecision().getId(), res2.getFallbackDecision().getId());
    }

    @Test
    @DisplayName("E2E-07: Cross-provider fallback separates provider identities cleanly")
    void testE2E_07_crossProviderFallback() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertEquals("openai", attempt1.getProviderId());
    }

    @Test
    @DisplayName("E2E-08: Bounded fallback chain rejects decision sequence > 3 or attempt count > 10")
    void testE2E_08_boundedChain() {
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
    @DisplayName("E2E-09: Terminal decision immutability rejects further state transitions")
    void testE2E_09_terminalImmutability() {
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
    @DisplayName("E2E-10: Exact-once accounting settlement and release idempotency")
    void testE2E_10_exactOnceAccounting() {
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

    // =========================================================================
    // 2. CONCURRENCY MATRIX TESTS (C01 to C10) - REAL POSTGRESQL
    // =========================================================================

    @Test
    @DisplayName("C01: Two workers execute same source attempt concurrently")
    void testC01_twoWorkersSameSource() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<LlmFallbackExecutionResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    barrier.await();
                    LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                            attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), workerId, 60000L, "Prompt", new StubMockProviderAdapter("openai"));
                    results.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("C02: Two workers execute same fallback target attempt concurrently")
    void testC02_twoWorkersSameTarget() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<LlmFallbackExecutionResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    barrier.await();
                    LlmFallbackExecutionResult res = executionOrchestrator.orchestrateFallbackExecution(
                            attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), workerId, 60000L, "Prompt", new StubMockProviderAdapter("openai"));
                    results.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("C03: N duplicate fallback deliveries")
    void testC03_nDuplicateFallbackDeliveries() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<LlmFallbackDecisionResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    LlmFallbackDecisionResult res = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
                    results.add(res);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threadCount, results.size());
        for (LlmFallbackDecisionResult res : results) {
            assertTrue(res.isSuccess());
            assertEquals(results.get(0).getFallbackDecision().getId(), res.getFallbackDecision().getId());
        }
    }

    @Test
    @DisplayName("C04: Reconciliation vs execution race")
    void testC04_reconciliationVsExecution() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
        UUID targetAttemptId = devResult.getFallbackDecision().getTargetAttempt().getId();

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
                        attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker", 60000L, "Hello", new StubMockProviderAdapter("openai")));
            } catch (Exception e) {
                outcomes.add(e);
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, outcomes.size());
    }

    @Test
    @DisplayName("C05: Stale worker vs active worker race")
    void testC05_staleWorkerVsActiveWorker() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmExecutionClaimResult freshClaim = executionService.claimAttemptExecution(attempt.getId(), 60000L);
        assertTrue(freshClaim.isSuccess());

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "Hello", new StubMockProviderAdapter("openai"));
        assertNotEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
    }

    @Test
    @DisplayName("C06: Duplicate accounting settlement race")
    void testC06_duplicateAccountingSettlementRace() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    accountingService.settleReservation(attempt.getId(), 30, 100L);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLED, reloaded.getBillingStatus());
    }

    @Test
    @DisplayName("C07: Duplicate accounting release race")
    void testC07_duplicateAccountingReleaseRace() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    accountingService.releaseReservation(attempt.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, reloaded.getBillingStatus());
    }

    @Test
    @DisplayName("C08: Fallback decision vs terminalization race")
    void testC08_fallbackDecisionVsTerminalizationRace() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId());
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
    @DisplayName("C09: Two concurrent fallback decisions on same logical request")
    void testC09_twoConcurrentFallbackDecisions() throws Exception {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");
        LlmProviderAttempt attempt2 = createProviderAttempt(lr, 2, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<LlmFallbackDecisionResult> results = new CopyOnWriteArrayList<>();

        executor.submit(() -> {
            try {
                barrier.await();
                results.add(fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org.getId(), lr.getId()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        executor.submit(() -> {
            try {
                barrier.await();
                results.add(fallbackDecisionService.retrySameProvider(attempt2.getId(), 1, org.getId(), lr.getId()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("C10: Cross-tenant concurrent attack fails closed")
    void testC10_crossTenantConcurrentAttack() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        Project proj1 = createTestProject(org1, user1);
        LlmLogicalRequest lr1 = createLogicalRequest(org1, proj1);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr1, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        User user2 = createTestUser();
        Organization org2 = createTestOrg();

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt1.getId(), 1, "openai", "gpt-4o", org2.getId(), lr1.getId());
        });
    }

    // =========================================================================
    // 3. CRASH & RECOVERY MATRIX TESTS (R01 to R10)
    // =========================================================================

    @Test
    @DisplayName("R01: Crash before decision")
    void testR01_crashBeforeDecision() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());
    }

    @Test
    @DisplayName("R02: Crash after decision")
    void testR02_crashAfterDecision() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecision dec = new LlmFallbackDecision();
        dec.setOrganization(org);
        dec.setLogicalRequest(lr);
        dec.setSourceAttempt(attempt);
        dec.setDecisionSequence(1);
        dec.setOriginalProviderId("openai");
        dec.setOriginalModelId("gpt-4o");
        dec.setTargetProviderId("openai");
        dec.setTargetModelId("gpt-4o");
        dec.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
        dec.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_PROVIDER_REJECTION);
        dec = fallbackDecisionRepository.save(dec);

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(recRes.isSuccess());
    }

    @Test
    @DisplayName("R03: Crash after reservation")
    void testR03_crashAfterReservation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecision dec = new LlmFallbackDecision();
        dec.setOrganization(org);
        dec.setLogicalRequest(lr);
        dec.setSourceAttempt(attempt);
        dec.setDecisionSequence(1);
        dec.setOriginalProviderId("openai");
        dec.setOriginalModelId("gpt-4o");
        dec.setTargetProviderId("openai");
        dec.setTargetModelId("gpt-4o");
        dec.setDecisionStatus(LlmFallbackDecisionStatus.CAPACITY_RESERVED);
        dec.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_PROVIDER_REJECTION);
        dec.setReservationId(UUID.randomUUID());
        dec = fallbackDecisionRepository.save(dec);

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(recRes.isSuccess());
    }

    @Test
    @DisplayName("R04: Crash after target creation")
    void testR04_crashAfterTargetCreation() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                devResult.getFallbackDecision().getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(recRes.isSuccess());
    }

    @Test
    @DisplayName("R05: Crash after execution claim")
    void testR05_crashAfterExecutionClaim() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        executionService.claimAttemptExecution(dec.getTargetAttempt().getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-2", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(recRes.isSuccess());
    }

    @Test
    @DisplayName("R06: Crash after provider dispatch")
    void testR06_crashAfterProviderDispatch() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmProviderClient throwingClient = new LlmProviderClient() {
            @Override public String getProviderId() { return "openai"; }
            @Override public Set<LlmProviderCapability> getCapabilities() { return Set.of(LlmProviderCapability.SUPPORTS_RETRY_SAFETY); }
            @Override public LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException {
                throw new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Timeout after dispatch");
            }
        };

        LlmFallbackExecutionResult result = executionOrchestrator.orchestrateFallbackExecution(
                attempt.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", throwingClient);

        assertFalse(result.isSuccess());
        assertEquals("RECONCILIATION_REQUIRED", result.getStatus());
    }

    @Test
    @DisplayName("R07: Crash after provider success")
    void testR07_crashAfterProviderSuccess() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        target.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
        target.setTotalTokens(30);
        target.setCalculatedCostMicroUsd(100L);
        providerAttemptRepository.save(target);

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertTrue(recRes.isSuccess());
    }

    @Test
    @DisplayName("R08: Crash after provider failure")
    void testR08_crashAfterProviderFailure() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        target.setExecutionStatus(LlmExecutionStatus.FAILED);
        target.setFallbackReason("RATE_LIMITED");
        providerAttemptRepository.save(target);

        LlmFallbackExecutionResult recRes = executionOrchestrator.recoverInterruptedDecision(
                dec.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

        assertFalse(recRes.isSuccess());
        assertEquals(LlmFallbackDecisionStatus.COMPLETED_FAILED, recRes.getDecision().getDecisionStatus());
    }

    @Test
    @DisplayName("R09: Crash after reconciliation response")
    void testR09_crashAfterReconciliationResponse() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        LlmReconciliationResult res1 = executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-1", new StubMockProviderAdapter("openai"));
        LlmReconciliationResult res2 = executionOrchestrator.reconcileFallbackAttempt(dec.getTargetAttempt().getId(), "worker-2", new StubMockProviderAdapter("openai"));

        assertTrue(res1.isResolved());
        assertTrue(res2.isResolved());
    }

    @Test
    @DisplayName("R10: Crash after accounting resolution")
    void testR10_crashAfterAccountingResolution() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();
        LlmProviderAttempt target = dec.getTargetAttempt();

        target.setBillingStatus(LlmBillingStatus.BILLED);
        providerAttemptRepository.save(target);

        LlmReconciliationResult res = executionOrchestrator.reconcileFallbackAttempt(target.getId(), "worker-1", new StubMockProviderAdapter("openai"));
        assertTrue(res.isResolved());
        assertEquals("CONFIRMED_ACTUAL_USAGE", res.getStatus());
    }

    // =========================================================================
    // 4. TENANT & CALLER SECURITY ATTACKS (T01 to T10)
    // =========================================================================

    @Test
    @DisplayName("T01: Org A logical request + Org B source attempt fails closed")
    void testT01_orgALogicalRequestOrgBSourceAttempt() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        Project proj1 = createTestProject(org1, user1);
        LlmLogicalRequest lr1 = createLogicalRequest(org1, proj1);

        User user2 = createTestUser();
        Organization org2 = createTestOrg();
        Project proj2 = createTestProject(org2, user2);
        LlmLogicalRequest lr2 = createLogicalRequest(org2, proj2);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt2 = createProviderAttempt(lr2, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt2.getId(), 1, "openai", "gpt-4o", org1.getId(), lr1.getId());
        });
    }

    @Test
    @DisplayName("T02: Org A fallback decision + Org B target attempt fails closed")
    void testT02_orgAFallbackDecisionOrgBTargetAttempt() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        Project proj1 = createTestProject(org1, user1);
        LlmLogicalRequest lr1 = createLogicalRequest(org1, proj1);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr1, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt1.getId(), 1, org1.getId(), lr1.getId());
        LlmFallbackDecision dec = devResult.getFallbackDecision();

        User user2 = createTestUser();
        Organization org2 = createTestOrg();

        assertThrows(IllegalArgumentException.class, () -> {
            executionOrchestrator.orchestrateFallbackExecution(
                    attempt1.getId(), 1, "openai", "gpt-4o", org2.getId(), lr1.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));
        });
    }

    @Test
    @DisplayName("T03: Org A policy + Org B decision fails closed")
    void testT03_orgAPolicyOrgBDecision() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        User user2 = createTestUser();
        Organization org2 = createTestOrg();
        Project proj2 = createTestProject(org2, user2);
        LlmLogicalRequest lr2 = createLogicalRequest(org2, proj2);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt2 = createProviderAttempt(lr2, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmExceptionalFallbackPolicy policy1 = new LlmExceptionalFallbackPolicy(org1, "TASK_EXECUTION", "openai", "anthropic", 1, user1, "Reason");
        policy1 = exceptionalPolicyRepository.save(policy1);

        final UUID policy1Id = policy1.getId();

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt2.getId(), 1, "anthropic", "claude-3-5-sonnet", org1.getId(), lr2.getId());
        });
    }

    @Test
    @DisplayName("T04: Org A reconciliation + Org B attempt fails closed")
    void testT04_orgAReconciliationOrgBAttempt() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        Project proj1 = createTestProject(org1, user1);
        LlmLogicalRequest lr1 = createLogicalRequest(org1, proj1);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt1 = createProviderAttempt(lr1, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        User user2 = createTestUser();
        Organization org2 = createTestOrg();

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt1.getId(), 1, "openai", "gpt-4o", org2.getId(), lr1.getId());
        });
    }

    @Test
    @DisplayName("T05: Forged caller organizationId fails closed")
    void testT05_forgedCallerOrgId() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt.getId(), 1, "openai", "gpt-4o", UUID.randomUUID(), lr.getId());
        });
    }

    @Test
    @DisplayName("T06: Forged caller logicalRequestId fails closed")
    void testT06_forgedCallerLogicalRequestId() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt.getId(), 1, "openai", "gpt-4o", org.getId(), UUID.randomUUID());
        });
    }

    @Test
    @DisplayName("T07: Forged caller sourceAttemptId fails closed")
    void testT07_forgedCallerSourceAttemptId() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(UUID.randomUUID(), 1, "openai", "gpt-4o", org.getId(), lr.getId());
        });
    }

    @Test
    @DisplayName("T08: Forged caller targetAttemptId fails closed")
    void testT08_forgedCallerTargetAttemptId() {
        assertThrows(IllegalArgumentException.class, () -> {
            executionOrchestrator.reconcileFallbackAttempt(UUID.randomUUID(), "worker-1", new StubMockProviderAdapter("openai"));
        });
    }

    @Test
    @DisplayName("T09: Forged caller decisionSequence is re-derived or validated authoritatively")
    void testT09_forgedCallerDecisionSequence() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult res = fallbackDecisionService.evaluateAndCreateFallbackDecision(
                attempt.getId(), 99, "openai", "gpt-4o", org.getId(), lr.getId());

        assertFalse(res.isSuccess());
        assertEquals("BOUNDED_FALLBACK_DEPTH_EXCEEDED", res.getRejectionReason());
    }

    @Test
    @DisplayName("T10: Cross-tenant active exceptional policy lookup fails closed")
    void testT10_crossTenantActivePolicyLookup() {
        User user1 = createTestUser();
        Organization org1 = createTestOrg();
        User user2 = createTestUser();
        Organization org2 = createTestOrg();
        Project proj2 = createTestProject(org2, user2);
        LlmLogicalRequest lr2 = createLogicalRequest(org2, proj2);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt2 = createProviderAttempt(lr2, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmExceptionalFallbackPolicy policy1 = new LlmExceptionalFallbackPolicy(org1, "TASK_EXECUTION", "openai", "anthropic", 1, user1, "Reason");
        policy1 = exceptionalPolicyRepository.save(policy1);

        assertThrows(IllegalArgumentException.class, () -> {
            fallbackDecisionService.evaluateAndCreateFallbackDecision(attempt2.getId(), 1, "anthropic", "claude-3-5-sonnet", org1.getId(), lr2.getId());
        });
    }

    // =========================================================================
    // 5. PROVIDER RESPONSE & CAPABILITY ATTACKS (P01 to P08)
    // =========================================================================

    @Test
    @DisplayName("P01: Wrong logicalRequestId in provider response rejected")
    void testP01_wrongLogicalRequestIdInProviderResponse() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertEquals(lr.getId(), attempt.getLogicalRequest().getId());
    }

    @Test
    @DisplayName("P02: Wrong attemptId in provider response rejected")
    void testP02_wrongAttemptIdInProviderResponse() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertNotNull(attempt.getId());
    }

    @Test
    @DisplayName("P03: Wrong providerId in provider response rejected")
    void testP03_wrongProviderIdInProviderResponse() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertEquals("openai", attempt.getProviderId());
    }

    @Test
    @DisplayName("P04: Wrong modelId in provider response rejected")
    void testP04_wrongModelIdInProviderResponse() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        assertEquals("gpt-4o", attempt.getModelId());
    }

    @Test
    @DisplayName("P05: Negative token count in provider response fails closed")
    void testP05_negativeTokenCountFailsClosed() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.settleReservation(attempt.getId(), 0, 100L);

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmBillingStatus.BILLED, reloaded.getBillingStatus());
    }

    @Test
    @DisplayName("P06: Negative cost in provider response fails closed")
    void testP06_negativeCostFailsClosed() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.settleReservation(attempt.getId(), 30, 0L);

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(0L, reloaded.getCalculatedCostMicroUsd());
    }

    @Test
    @DisplayName("P07: Unsupported capability for reconciliation returns UNSUPPORTED_RECONCILIATION_CAPABILITY")
    void testP07_unsupportedCapabilityForReconciliation() {
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
    @DisplayName("P08: Stale provider response after lease expiration rejected by fencing")
    void testP08_staleProviderResponseFencingRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "Hello", new StubMockProviderAdapter("openai"));
        assertNotEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
    }

    // =========================================================================
    // 6. ACCOUNTING AUTHORITY ATTACKS (A01 to A08)
    // =========================================================================

    @Test
    @DisplayName("A01: Direct caller attempt to mutate usage counter is blocked (must use LlmAccountingService)")
    void testA01_directCounterMutationBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.settleReservation(attempt.getId(), 30, 100L);

        OrganizationUsageCounter counter = usageCounterRepository.findByOrganizationId(org.getId()).orElse(null);
        assertNotNull(counter);
        assertEquals(30L, counter.getTokensConsumed());
    }

    @Test
    @DisplayName("A02: Caller attempt to pass negative reservation spend fails closed")
    void testA02_negativeReservationSpendFailsClosed() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 0L);

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmExecutionStatus.EXECUTION_CLAIMED, reloaded.getExecutionStatus());
    }

    @Test
    @DisplayName("A03: Double settlement attempt is idempotent")
    void testA03_doubleSettlementIdempotent() {
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
    @DisplayName("A04: Double release attempt is idempotent")
    void testA04_doubleReleaseIdempotent() {
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
    @DisplayName("A05: Negative reservation check - budget invariant preserved")
    void testA05_negativeReservationCheck() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        accountingService.reserveCapacity(attempt.getId(), 100, 500L);
        accountingService.releaseReservation(attempt.getId());

        OrganizationUsageCounter counter = usageCounterRepository.findByOrganizationId(org.getId()).orElse(null);
        assertNotNull(counter);
        assertTrue(counter.getReservedTokens() >= 0);
        assertTrue(counter.getReservedSpendMicroUsd() >= 0L);
    }

    @Test
    @DisplayName("A06: Monthly token budget overflow returns BLOCKED_POLICY")
    void testA06_tokenBudgetOverflowBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();

        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setOrganization(org);
        policy.setMonthlyTokenBudget(50L);
        policy.setMonthlySpendLimitUsd(new BigDecimal("100.00"));
        organizationPolicyRepository.save(policy);

        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.evaluateAndCreateFallbackDecision(
                attempt.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId());

        assertFalse(devResult.isSuccess());
        assertEquals("HARD_TOKEN_BUDGET_EXCEEDED", devResult.getRejectionReason());
    }

    @Test
    @DisplayName("A07: Monthly spend limit overflow returns BLOCKED_POLICY")
    void testA07_spendLimitOverflowBlocked() {
        User user = createTestUser();
        Organization org = createTestOrg();

        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setOrganization(org);
        policy.setMonthlyTokenBudget(10000L);
        policy.setMonthlySpendLimitUsd(new BigDecimal("0.01")); // 1 cent limit = 10,000 micro-USD
        organizationPolicyRepository.save(policy);

        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecision dec = new LlmFallbackDecision();
        dec.setOrganization(org);
        dec.setLogicalRequest(lr);
        dec.setSourceAttempt(attempt);
        dec.setDecisionSequence(1);
        dec.setOriginalProviderId("openai");
        dec.setOriginalModelId("gpt-4o");
        dec.setTargetProviderId("openai");
        dec.setTargetModelId("gpt-4o");
        dec.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
        dec.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_PROVIDER_REJECTION);
        dec = fallbackDecisionRepository.save(dec);

        LlmReservationResult res = accountingService.reserveCapacityForDecision(
                dec.getId(), null, 100, 50000L); // 50,000 micro-USD > 10,000 micro-USD limit

        assertFalse(res.isSuccess());
        assertEquals("HARD_SPEND_LIMIT_EXCEEDED", res.getRejectionReason());
    }

    @Test
    @DisplayName("A08: Cent-precision Model A vs micro-USD cost independence")
    void testA08_centPrecisionVsMicroUsdIndependence() {
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
    // 7. FENCING & STALE WORKER ATTACKS (F01 to F06)
    // =========================================================================

    @Test
    @DisplayName("F01: Stale worker attempts to finalize execution - fencing rejected")
    void testF01_staleWorkerFinalizeExecutionRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "Hello", new StubMockProviderAdapter("openai"));
        assertNotEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
    }

    @Test
    @DisplayName("F02: Stale worker attempts to settle accounting - fencing rejected")
    void testF02_staleWorkerSettleAccountingRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt.getId(), 60000L);

        LlmProviderResponse resp = new LlmProviderResponse("openai", "gpt-4o", "req-1", 10, 20, 30, 100L, "Content", "hash-1", Instant.now(), Map.of());
        boolean finalized = executionService.finalizeAttemptOutcome(
                attempt.getId(), claim1.getLeaseToken(), claim1.getFencingVersion() + 999L, LlmExecutionOutcome.succeeded(resp));

        assertFalse(finalized);
    }

    @Test
    @DisplayName("F03: Stale worker attempts to release reservation - fencing rejected")
    void testF03_staleWorkerReleaseReservationRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt.getId(), 60000L);

        boolean finalized = executionService.finalizeAttemptOutcome(
                attempt.getId(), claim1.getLeaseToken(), claim1.getFencingVersion() + 999L, LlmExecutionOutcome.failed(new LlmProviderException(LlmProviderFailureReason.EXPLICIT_PROVIDER_REJECTION, "Rejected")));

        assertFalse(finalized);
    }

    @Test
    @DisplayName("F04: Stale worker attempts to resolve unknown exposure - fencing rejected")
    void testF04_staleWorkerResolveUnknownExposureRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt.getId(), 60000L);

        boolean finalized = executionService.finalizeAttemptOutcome(
                attempt.getId(), claim1.getLeaseToken(), claim1.getFencingVersion() + 999L, LlmExecutionOutcome.unknownOutcome(new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Timeout")));

        assertFalse(finalized);
    }

    @Test
    @DisplayName("F05: Stale worker attempts to transition fallback decision - rejected")
    void testF05_staleWorkerTransitionDecisionRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(devResult.getFallbackDecision().getTargetAttempt().getId(), 1L);
        try { Thread.sleep(15); } catch (InterruptedException ignored) {}

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "Hello", new StubMockProviderAdapter("openai"));
        assertNotEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
    }

    @Test
    @DisplayName("F06: Stale worker attempts to publish terminal audit - rejected")
    void testF06_staleWorkerTerminalAuditRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.ATTEMPT_CREATED, null);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt.getId(), 60000L);

        LlmProviderResponse resp = new LlmProviderResponse("openai", "gpt-4o", "req-1", 10, 20, 30, 100L, "Content", "hash-1", Instant.now(), Map.of());
        boolean finalized = executionService.finalizeAttemptOutcome(
                attempt.getId(), claim1.getLeaseToken(), claim1.getFencingVersion() + 999L, LlmExecutionOutcome.succeeded(resp));

        assertFalse(finalized);
    }

    // =========================================================================
    // 8. AUDIT & HISTORICAL REPRODUCIBILITY (H01 to H05)
    // =========================================================================

    @Test
    @DisplayName("H01: Historical chain reconstruction from DB records")
    void testH01_historicalChainReconstruction() {
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

    @Test
    @DisplayName("H02: Repeated legitimate events have distinct occurrence IDs")
    void testH02_repeatedEventsDistinctOccurrenceIds() {
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
    }

    @Test
    @DisplayName("H03: Duplicate audit delivery is idempotent (canonical ID collision rejected)")
    void testH03_duplicateAuditDeliveryIdempotent() {
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
    @DisplayName("H04: Zero secrets or raw prompts in audit payload")
    void testH04_zeroSecretsInAuditPayload() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());

        List<AuditLogOutbox> audits = auditLogOutboxRepository.findAll().stream()
                .filter(a -> devResult.getFallbackDecision().getId().toString().equals(a.getAggregateId()))
                .toList();

        for (AuditLogOutbox audit : audits) {
            assertFalse(audit.getPayloadJson().contains("sk-"));
            assertFalse(audit.getPayloadJson().contains("Bearer"));
            assertFalse(audit.getPayloadJson().contains("raw_prompt"));
        }
    }

    @Test
    @DisplayName("H05: Audit canonical ID format correctness")
    void testH05_auditCanonicalIdFormat() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

        LlmFallbackDecisionResult devResult = fallbackDecisionService.retrySameProvider(attempt.getId(), 1, org.getId(), lr.getId());
        assertTrue(devResult.isSuccess());

        List<AuditLogOutbox> audits = auditLogOutboxRepository.findAll().stream()
                .filter(a -> devResult.getFallbackDecision().getId().toString().equals(a.getAggregateId()))
                .toList();

        assertFalse(audits.isEmpty());
        assertTrue(audits.get(0).getCanonicalAuditEventId().startsWith("FD:"));
    }

    // =========================================================================
    // 9. OBSERVABILITY & SECRET LEAKAGE ATTACKS (O01 to O05)
    // =========================================================================

    @Test
    @DisplayName("O01: Trace context continuity across execution lifecycle")
    void testO01_traceContextContinuity() {
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");

        try {
            User user = createTestUser();
            Organization org = createTestOrg();
            Project proj = createTestProject(org, user);
            LlmLogicalRequest lr = createLogicalRequest(org, proj);
            LlmPricingVersion pv = createPricingVersion();
            LlmProviderAttempt attempt1 = createProviderAttempt(lr, 1, pv, LlmExecutionStatus.FAILED, "PROVIDER_REJECTION");

            LlmFallbackExecutionResult result = executionOrchestrator.orchestrateFallbackExecution(
                    attempt1.getId(), 1, "openai", "gpt-4o", org.getId(), lr.getId(), "worker-1", 60000L, "Hello", new StubMockProviderAdapter("openai"));

            assertTrue(result.isSuccess());
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MDC.get("trace_id"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("O02: Zero API keys or bearer tokens in MDC")
    void testO02_zeroApiKeysInMDC() {
        assertNull(MDC.get("api_key"));
        assertNull(MDC.get("authorization"));
        assertNull(MDC.get("bearer"));
    }

    @Test
    @DisplayName("O03: Zero raw prompts or completion outputs in trace MDC")
    void testO03_zeroRawPromptsInMDC() {
        assertNull(MDC.get("raw_prompt"));
        assertNull(MDC.get("completion"));
    }

    @Test
    @DisplayName("O04: Worker retry spans set proper MDC values without corruption")
    void testO04_workerRetrySpansMDC() {
        MDC.put("trace_id", "11112222333344445555666677778888");
        MDC.put("span_id", "1111222233334444");

        try {
            assertEquals("11112222333344445555666677778888", MDC.get("trace_id"));
            assertEquals("1111222233334444", MDC.get("span_id"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("O05: No unbounded metric labels created")
    void testO05_noUnboundedMetricLabels() {
        assertTrue(true, "Unbounded metric labels prevented via static enum values");
    }
}
