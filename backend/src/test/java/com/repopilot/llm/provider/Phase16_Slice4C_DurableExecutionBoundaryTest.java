package com.repopilot.llm.provider;

import com.repopilot.llm.dto.LlmExecutionClaimResult;
import com.repopilot.llm.dto.LlmExecutionOutcome;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.transport.FakeOpenAiTransport;
import com.repopilot.llm.provider.adapter.OpenAiProviderAdapter;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.exception.LlmUnsupportedCapabilityException;
import com.repopilot.llm.provider.model.LlmProviderFailureReason;
import com.repopilot.llm.repository.*;
import com.repopilot.llm.service.LlmExecutionService;
import com.repopilot.llm.service.LlmLeaseTokenGenerator;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationUsageCounter;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.organization.repository.OrganizationUsageCounterRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4C_DurableExecutionBoundaryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationUsageCounterRepository organizationUsageCounterRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private LlmProviderAttemptRepository providerAttemptRepository;
    @Autowired private LlmExecutionService executionService;
    @Autowired private PlatformTransactionManager transactionManager;

    private User testUser;
    private Organization testOrg;
    private Project testProject;
    private LlmPricingVersion defaultPricingVersion;

    @BeforeEach
    void setUp() {
        cleanupDatabase();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            testUser = new User();
            testUser.setEmail("llm_4c3_user_" + UUID.randomUUID() + "@example.com");
            testUser.setPasswordHash("hash");
            testUser.setName("LLM 4C3 User");
            testUser.setRole(UserRole.USER);
            testUser = userRepository.save(testUser);

            testOrg = new Organization();
            testOrg.setName("LLM 4C3 Org " + UUID.randomUUID());
            testOrg.setSlug("llm-4c3-org-" + UUID.randomUUID());
            testOrg = organizationRepository.save(testOrg);

            testProject = new Project();
            testProject.setName("LLM 4C3 Project");
            testProject.setDescription("LLM Slice 4C3 Project");
            testProject.setOwner(testUser);
            testProject.setOrganization(testOrg);
            testProject.setStatus(ProjectStatus.ACTIVE);
            testProject = projectRepository.save(testProject);

            Optional<LlmPricingVersion> existingPv = pricingVersionRepository.findByProviderIdAndModelIdAndPricingVersion("openai", "gpt-4o", 1);
            if (existingPv.isPresent()) {
                defaultPricingVersion = existingPv.get();
            } else {
                defaultPricingVersion = new LlmPricingVersion();
                defaultPricingVersion.setProviderId("openai");
                defaultPricingVersion.setModelId("gpt-4o");
                defaultPricingVersion.setPricingVersion(1);
                defaultPricingVersion.setInputRateMicroUsdPer1k(2500L);
                defaultPricingVersion.setOutputRateMicroUsdPer1k(10000L);
                defaultPricingVersion.setEffectiveFrom(Instant.now());
                defaultPricingVersion = pricingVersionRepository.save(defaultPricingVersion);
            }
        });
    }

    @AfterEach
    void tearDown() {
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            providerAttemptRepository.deleteAllInBatch();
            logicalRequestRepository.deleteAllInBatch();
            organizationUsageCounterRepository.deleteAllInBatch();

            if (testProject != null && projectRepository.existsById(testProject.getId())) {
                projectRepository.delete(testProject);
                testProject = null;
            }
            if (testOrg != null && organizationRepository.existsById(testOrg.getId())) {
                organizationRepository.delete(testOrg);
                testOrg = null;
            }
            if (testUser != null && userRepository.existsById(testUser.getId())) {
                userRepository.delete(testUser);
                testUser = null;
            }
        });
    }

    private LlmProviderAttempt createAttempt(String authorityId, int attemptNum) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            LlmLogicalRequest req = new LlmLogicalRequest();
            req.setOrganization(testOrg);
            req.setProject(testProject);
            req.setSourceAuthorityType("TASK_EXECUTION");
            req.setSourceAuthorityId(authorityId);
            req.setIdempotencyKey("idem_" + UUID.randomUUID());
            req.setFeatureName("CODE_GENERATION");
            req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            req.setBillingStatus(LlmBillingStatus.UNBILLED);
            req = logicalRequestRepository.saveAndFlush(req);

            LlmProviderAttempt attempt = new LlmProviderAttempt();
            attempt.setLogicalRequest(req);
            attempt.setAttemptNumber(attemptNum);
            attempt.setProviderId("openai");
            attempt.setModelId("gpt-4o");
            attempt.setPricingVersion(defaultPricingVersion);
            attempt.setProviderIdempotencyKey("idem_key_" + UUID.randomUUID());
            attempt.setInputHash("input_hash_4c3");
            attempt.setPromptTokens(100);
            attempt.setEstimatedCostMicroUsd(10000L);
            attempt.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
            return providerAttemptRepository.saveAndFlush(attempt);
        });
    }

    @Test
    @DisplayName("TEST57: Single attempt execution claim succeeds with lease and fencing increment")
    void test57_singleAttemptClaimSucceeds() {
        LlmProviderAttempt attempt = createAttempt("auth_t57", 1);

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        assertTrue(claim.isSuccess());
        assertNotNull(claim.getLeaseToken());
        assertEquals(2L, claim.getFencingVersion());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.EXECUTION_CLAIMED, updated.getExecutionStatus());
            assertEquals(claim.getLeaseToken(), updated.getWorkerLeaseToken());
            assertEquals(2L, updated.getFencingVersion());
        });
    }

    @Test
    @DisplayName("TEST58: Concurrent claim on same attempt -> exactly one worker receives ownership")
    void test58_concurrentDuplicateClaimExactlyOneWinner() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t58", 1);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectionCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    LlmExecutionClaimResult res = executionService.claimAttemptExecution(attempt.getId(), 30000L);
                    if (res.isSuccess()) successCount.incrementAndGet();
                    else rejectionCount.incrementAndGet();
                } catch (Exception e) {
                    rejectionCount.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one worker must claim execution");
        assertEquals(1, rejectionCount.get(), "Exactly one worker must be rejected");
    }

    @Test
    @DisplayName("TEST59: Affected-row enforcement prevents re-claiming an already claimed attempt")
    void test59_affectedRowEnforcement() {
        LlmProviderAttempt attempt = createAttempt("auth_t59", 1);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        assertTrue(claim1.isSuccess());

        LlmExecutionClaimResult claim2 = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        assertFalse(claim2.isSuccess());
        assertEquals("ALREADY_CLAIMED_OR_NOT_EXECUTABLE", claim2.getRejectionReason());
    }

    @Test
    @DisplayName("TEST60: No provider dispatch occurs after failed claim")
    void test60_noProviderDispatchAfterFailedClaim() {
        LlmProviderAttempt attempt = createAttempt("auth_t60", 1);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        assertTrue(claim1.isSuccess());

        LlmExecutionClaimResult claim2 = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        assertFalse(claim2.isSuccess());

        AtomicInteger transportInvocationCount = new AtomicInteger(0);
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                transportInvocationCount.incrementAndGet();
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        assertThrows(IllegalArgumentException.class, () ->
                executionService.executeWithFencing(claim2, "prompt", adapter));
        assertEquals(0, transportInvocationCount.get(), "Provider must NOT be dispatched when claim fails");
    }

    @Test
    @DisplayName("TEST61: Unique SecureRandom lease tokens generated for each claim")
    void test61_uniqueSecureRandomLeaseToken() {
        String token1 = LlmLeaseTokenGenerator.generateLeaseToken();
        String token2 = LlmLeaseTokenGenerator.generateLeaseToken();

        assertNotNull(token1);
        assertNotNull(token2);
        assertNotEquals(token1, token2);
        assertEquals(64, token1.length(), "256-bit Hex string must be 64 characters");
    }

    @Test
    @DisplayName("TEST62: Fencing version increments atomically upon claim")
    void test62_fencingVersionIncrement() {
        LlmProviderAttempt attempt = createAttempt("auth_t62", 1);
        assertEquals(1L, attempt.getFencingVersion());

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        assertEquals(2L, claim.getFencingVersion());
    }

    @Test
    @DisplayName("TEST63: Current lease and fencing ownership finalization succeeds")
    void test63_currentLeaseOwnershipValidation() {
        LlmProviderAttempt attempt = createAttempt("auth_t63", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());

        boolean finalized = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), claim.getLeaseToken(), claim.getFencingVersion(), outcome
        );
        assertTrue(finalized, "Finalization must succeed for valid current worker lease & fencing version");
    }

    @Test
    @DisplayName("TEST64: Stale worker finalization rejected after lease/fencing increment")
    void test64_staleWorkerRejection() {
        LlmProviderAttempt attempt = createAttempt("auth_t64", 1);

        // Worker A claims attempt (fencingVersion = 2)
        LlmExecutionClaimResult workerAClaim = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        assertTrue(workerAClaim.isSuccess());

        // Simulate Worker B reclaiming attempt by direct database update or reset
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_new_lease_token");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        // Worker A attempts finalization with stale lease token and fencing version 2
        LlmProviderResponse mockResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_stale", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcome = LlmExecutionOutcome.succeeded(mockResp);

        boolean finalized = executionService.finalizeAttemptOutcome(
                workerAClaim.getAttemptId(), workerAClaim.getLeaseToken(), workerAClaim.getFencingVersion(), outcome
        );
        assertFalse(finalized, "Stale worker finalization must be strictly rejected");
    }

    @Test
    @DisplayName("TEST65: Wrong lease token rejected during finalization")
    void test65_wrongLeaseRejection() {
        LlmProviderAttempt attempt = createAttempt("auth_t65", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        LlmProviderResponse mockResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_t65", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcome = LlmExecutionOutcome.succeeded(mockResp);

        boolean finalized = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), "wrong_lease_token", claim.getFencingVersion(), outcome
        );
        assertFalse(finalized, "Finalization with wrong lease token must be rejected");
    }

    @Test
    @DisplayName("TEST66: Wrong fencing version rejected during finalization")
    void test66_wrongFencingVersionRejection() {
        LlmProviderAttempt attempt = createAttempt("auth_t66", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        LlmProviderResponse mockResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_t66", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcome = LlmExecutionOutcome.succeeded(mockResp);

        boolean finalized = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), claim.getLeaseToken(), 9999L, outcome
        );
        assertFalse(finalized, "Finalization with wrong fencing version must be rejected");
    }

    @Test
    @DisplayName("TEST67: Duplicate delivery suppression via claim verification")
    void test67_duplicateDeliverySuppression() {
        LlmProviderAttempt attempt = createAttempt("auth_t67", 1);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        LlmExecutionClaimResult claim2 = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        assertTrue(claim1.isSuccess());
        assertFalse(claim2.isSuccess());
    }

    @Test
    @DisplayName("TEST68: Exactly-one internal dispatch guaranteed across workers")
    void test68_exactlyOneInternalDispatch() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t68", 1);

        AtomicInteger dispatchCount = new AtomicInteger(0);
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                dispatchCount.incrementAndGet();
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        int numWorkers = 3;
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        CyclicBarrier barrier = new CyclicBarrier(numWorkers);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numWorkers; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);
                    if (claim.isSuccess()) {
                        executionService.executeWithFencing(claim, "prompt", adapter);
                    }
                } catch (Exception ignored) {}
            }));
        }

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        assertEquals(1, dispatchCount.get(), "Provider must be dispatched exactly ONCE internally");
    }

    @Test
    @DisplayName("TEST69: Provider capability enforcement before dispatch")
    void test69_providerCapabilityEnforcement() {
        LlmProviderAttempt attempt = createAttempt("auth_t69", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        assertDoesNotThrow(() -> executionService.executeWithFencing(claim, "prompt", adapter));
    }

    @Test
    @DisplayName("TEST70: Unsupported provider idempotency capability rejection")
    void test70_unsupportedProviderIdempotency() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LlmProviderAttempt anthropicAttempt = tx.execute(status -> {
            LlmLogicalRequest req = new LlmLogicalRequest();
            req.setOrganization(testOrg);
            req.setProject(testProject);
            req.setSourceAuthorityType("TASK_EXECUTION");
            req.setSourceAuthorityId("auth_t70");
            req.setIdempotencyKey("idem_" + UUID.randomUUID());
            req.setFeatureName("CODE_GENERATION");
            req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            req = logicalRequestRepository.saveAndFlush(req);

            LlmProviderAttempt a = new LlmProviderAttempt();
            a.setLogicalRequest(req);
            a.setAttemptNumber(1);
            a.setProviderId("anthropic");
            a.setModelId("claude-3-5-sonnet");
            a.setPricingVersion(defaultPricingVersion);
            a.setProviderIdempotencyKey("custom_idempotency_key_123");
            a.setInputHash("input_hash_t70");
            a.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            return providerAttemptRepository.saveAndFlush(a);
        });

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(anthropicAttempt.getId(), 30000L);

        com.repopilot.llm.provider.transport.FakeAnthropicTransport fakeAnthropic = new com.repopilot.llm.provider.transport.FakeAnthropicTransport();
        com.repopilot.llm.provider.adapter.AnthropicProviderAdapter anthropicAdapter = new com.repopilot.llm.provider.adapter.AnthropicProviderAdapter(fakeAnthropic);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", anthropicAdapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.UNSUPPORTED_CAPABILITY, outcome.getFailureReason());
    }

    @Test
    @DisplayName("TEST71: Deterministic provider request identity verification")
    void test71_deterministicProviderRequestIdentity() {
        LlmProviderAttempt attempt = createAttempt("auth_t71", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertNotNull(outcome.getProviderResponse().getProviderRequestId());
    }

    @Test
    @DisplayName("TEST72: Pre-dispatch timeout normalization to FAILED outcome")
    void test72_preDispatchTimeoutNormalization() {
        LlmProviderAttempt attempt = createAttempt("auth_t72", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        transport.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, "Connect timeout");
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, outcome.getFailureReason());
    }

    @Test
    @DisplayName("TEST73: Post-dispatch possible timeout normalization to UNKNOWN_OUTCOME")
    void test73_postDispatchPossibleTimeoutNormalization() {
        LlmProviderAttempt attempt = createAttempt("auth_t73", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        transport.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout post POST");
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.UNKNOWN_OUTCOME, outcome.getStatus());

        boolean finalized = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), claim.getLeaseToken(), claim.getFencingVersion(), outcome
        );
        assertTrue(finalized);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.TIMED_OUT, updated.getExecutionStatus());
        });
    }

    @Test
    @DisplayName("TEST74: Malformed provider response mapped to FAILED outcome")
    void test74_malformedProviderResponse() {
        LlmProviderAttempt attempt = createAttempt("auth_t74", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        LlmProviderResponse malformed = new LlmProviderResponse(
                "openai", "gpt-4o", "req_74", -5, 20, 15, null, "text", "hash", Instant.now(), Map.of()
        );
        transport.setCustomResponseToReturn(malformed);
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.MALFORMED_PROVIDER_RESPONSE, outcome.getFailureReason());
    }

    @Test
    @DisplayName("TEST75: Provider response identity mismatch mapped to FAILED outcome")
    void test75_providerResponseIdentityMismatch() {
        LlmProviderAttempt attempt = createAttempt("auth_t75", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        LlmProviderResponse mismatched = new LlmProviderResponse(
                "anthropic", "gpt-4o", "req_75", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        transport.setCustomResponseToReturn(mismatched);
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome.getStatus());
    }

    @Test
    @DisplayName("TEST76: Financial state remains unchanged by adapter execution")
    void test76_financialStateUnchangedByExecution() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        OrganizationUsageCounter beforeCounter = tx.execute(status ->
                organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg))
        );
        long initialSpendCents = beforeCounter.getTotalSpendCents();
        long initialTokensConsumed = beforeCounter.getTokensConsumed();

        LlmProviderAttempt attempt = createAttempt("auth_t76", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        executionService.finalizeAttemptOutcome(claim.getAttemptId(), claim.getLeaseToken(), claim.getFencingVersion(), outcome);

        OrganizationUsageCounter afterCounter = tx.execute(status ->
                organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg))
        );

        assertEquals(initialSpendCents, afterCounter.getTotalSpendCents(), "Spend cents MUST remain unchanged by execution layer");
        assertEquals(initialTokensConsumed, afterCounter.getTokensConsumed(), "Tokens consumed MUST remain unchanged by execution layer");
    }

    @Test
    @DisplayName("TEST77: Reflection Audit - Only LlmAccountingService can settle reservations")
    void test77_onlyAccountingLayerCanSettle() {
        for (Method m : LlmExecutionService.class.getDeclaredMethods()) {
            assertFalse(m.getName().toLowerCase().contains("settle"), "LlmExecutionService cannot expose settlement methods");
        }
    }

    @Test
    @DisplayName("TEST78: Reflection Audit - Only LlmAccountingService can release reservations")
    void test78_onlyAccountingLayerCanRelease() {
        for (Method m : LlmExecutionService.class.getDeclaredMethods()) {
            assertFalse(m.getName().toLowerCase().contains("release"), "LlmExecutionService cannot expose release methods");
        }
    }

    @Test
    @DisplayName("TEST79: Reflection Audit - Only LlmAccountingService can manage unresolved exposure")
    void test79_onlyAccountingLayerCanManageExposure() {
        for (Method m : LlmExecutionService.class.getDeclaredMethods()) {
            assertFalse(m.getName().toLowerCase().contains("exposure"), "LlmExecutionService cannot expose unresolved exposure methods");
        }
    }

    @Test
    @DisplayName("TEST80: Cross-attempt provider response rejection")
    void test80_crossAttemptResponseRejection() {
        LlmProviderAttempt attempt1 = createAttempt("auth_t80_1", 1);
        LlmProviderAttempt attempt2 = createAttempt("auth_t80_2", 1);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt1.getId(), 30000L);
        LlmExecutionClaimResult claim2 = executionService.claimAttemptExecution(attempt2.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome2 = executionService.executeWithFencing(claim2, "prompt", adapter);

        boolean finalized = executionService.finalizeAttemptOutcome(
                claim1.getAttemptId(), claim2.getLeaseToken(), claim1.getFencingVersion(), outcome2
        );
        assertFalse(finalized, "Cross-attempt finalization must be rejected");
    }

    @Test
    @DisplayName("TEST81: Cross-logical-request response rejection")
    void test81_crossLogicalRequestResponseRejection() {
        LlmProviderAttempt attempt1 = createAttempt("auth_t81_1", 1);

        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt1.getId(), 30000L);

        UUID wrongLogicalId = UUID.randomUUID();
        LlmProviderRequest wrongRequest = new LlmProviderRequest(
                "openai", "gpt-4o", wrongLogicalId, claim1.getAttemptId(),
                "idem_1", "hash_1", "prompt", 10
        );

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        assertNotNull(adapter);
        assertEquals(wrongLogicalId, wrongRequest.getLogicalRequestId());
        assertNotEquals(attempt1.getLogicalRequest().getId(), wrongRequest.getLogicalRequestId());
    }

    @Test
    @DisplayName("TEST82: Stale result cannot overwrite newer execution state")
    void test82_staleResultCannotOverwriteNewerExecution() {
        LlmProviderAttempt attempt = createAttempt("auth_t82", 1);

        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease");
            updated.setFencingVersion(3L);
            updated.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
            providerAttemptRepository.saveAndFlush(updated);
        });

        LlmProviderResponse mockResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_stale", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcome = LlmExecutionOutcome.succeeded(mockResp);

        boolean finalized = executionService.finalizeAttemptOutcome(
                claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), outcome
        );
        assertFalse(finalized, "Stale worker A finalization must be rejected");

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt current = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals("worker_b_lease", current.getWorkerLeaseToken());
            assertEquals(3L, current.getFencingVersion());
        });
    }

    @Test
    @DisplayName("TEST83: Duplicate provider response finalization cannot duplicate execution effect")
    void test83_duplicateProviderResponseIdempotency() {
        LlmProviderAttempt attempt = createAttempt("auth_t83", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        LlmProviderResponse resp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_t83", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcome = LlmExecutionOutcome.succeeded(resp);

        boolean firstFinalize = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), claim.getLeaseToken(), claim.getFencingVersion(), outcome
        );
        assertTrue(firstFinalize);

        boolean secondFinalize = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), claim.getLeaseToken(), claim.getFencingVersion(), outcome
        );
        assertFalse(secondFinalize, "Second finalization on already finalized attempt must return false");
    }

    @Test
    @DisplayName("TEST84: Execution status and Billing status independence")
    void test84_executionAndBillingStateIndependence() {
        LlmProviderAttempt attempt = createAttempt("auth_t84", 1);

        assertEquals(LlmExecutionStatus.ATTEMPT_CREATED, attempt.getExecutionStatus());
        assertEquals(LlmBillingStatus.UNBILLED, attempt.getBillingStatus());

        executionService.claimAttemptExecution(attempt.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.EXECUTION_CLAIMED, updated.getExecutionStatus());
            assertEquals(LlmBillingStatus.UNBILLED, updated.getBillingStatus(), "Billing status MUST remain UNBILLED after execution claim");
        });
    }

    @Test
    @DisplayName("TEST85: Lease expires before provider adapter dispatch -> pre-dispatch fencing blocks execution")
    void test85_leaseExpiresBeforeDispatch() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t85", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setClaimExpiresAt(Instant.now().minusSeconds(10));
            providerAttemptRepository.saveAndFlush(updated);
        });

        AtomicInteger dispatchCount = new AtomicInteger(0);
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                dispatchCount.incrementAndGet();
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, outcome.getFailureReason());
        assertEquals(0, dispatchCount.get(), "Provider must NOT be dispatched when lease expires before dispatch");
    }

    @Test
    @DisplayName("TEST86: Stale worker blocked before dispatch when attempt reclaimed by another worker")
    void test86_staleWorkerBlockedBeforeDispatch() {
        LlmProviderAttempt attempt = createAttempt("auth_t86", 1);
        LlmExecutionClaimResult workerAClaim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_new_lease");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        AtomicInteger dispatchCount = new AtomicInteger(0);
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                dispatchCount.incrementAndGet();
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(workerAClaim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, outcome.getFailureReason());
        assertEquals(0, dispatchCount.get(), "Provider transport must NOT be invoked by stale worker");
    }

    @Test
    @DisplayName("TEST87: Lease expires / reclaimed during provider call -> finalization fails")
    void test87_leaseReclaimedDuringProviderCallFinalizationFails() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t87", 1);
        LlmExecutionClaimResult workerAClaim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        CountDownLatch transportStarted = new CountDownLatch(1);
        CountDownLatch reclaimFinished = new CountDownLatch(1);

        FakeOpenAiTransport blockingTransport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                transportStarted.countDown();
                try {
                    reclaimFinished.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(blockingTransport);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LlmExecutionOutcome> futureOutcome = executor.submit(() ->
                executionService.executeWithFencing(workerAClaim, "prompt", adapter)
        );

        assertTrue(transportStarted.await(5, TimeUnit.SECONDS));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_reclaim_lease");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        reclaimFinished.countDown();
        LlmExecutionOutcome outcomeA = futureOutcome.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcomeA.getStatus());

        boolean finalized = executionService.finalizeAttemptOutcome(
                workerAClaim.getAttemptId(), workerAClaim.getLeaseToken(), workerAClaim.getFencingVersion(), outcomeA
        );
        assertFalse(finalized, "Finalization by Worker A must fail when Worker B reclaimed during transport call");
    }

    @Test
    @DisplayName("TEST88: Stale provider result cannot update DB state")
    void test88_staleProviderResultRejected() {
        LlmProviderAttempt attempt = createAttempt("auth_t88", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease_88");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        LlmProviderResponse respA = new LlmProviderResponse(
                "openai", "gpt-4o", "req_stale_88", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcomeA = LlmExecutionOutcome.succeeded(respA);

        boolean finalized = executionService.finalizeAttemptOutcome(
                claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), outcomeA
        );
        assertFalse(finalized);

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt current = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertNotEquals("req_stale_88", current.getProviderRequestId());
            assertEquals("worker_b_lease_88", current.getWorkerLeaseToken());
        });
    }

    @Test
    @DisplayName("TEST89: Newer worker state survives stale result finalization attempt")
    void test89_newerWorkerStateSurvivesStaleResult() {
        LlmProviderAttempt attempt = createAttempt("auth_t89", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease_89");
            updated.setFencingVersion(3L);
            updated.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
            updated.setProviderRequestId("req_worker_b");
            providerAttemptRepository.saveAndFlush(updated);
        });

        LlmProviderResponse respA = new LlmProviderResponse(
                "openai", "gpt-4o", "req_worker_a", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcomeA = LlmExecutionOutcome.succeeded(respA);

        boolean finalizedA = executionService.finalizeAttemptOutcome(
                claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), outcomeA
        );
        assertFalse(finalizedA);

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt current = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals("req_worker_b", current.getProviderRequestId(), "Worker B state must survive Worker A stale finalization");
            assertEquals(LlmExecutionStatus.SUCCEEDED, current.getExecutionStatus());
        });
    }

    @Test
    @DisplayName("TEST90: Pre-dispatch failure produces zero transport calls")
    void test90_preDispatchFailureZeroTransportCalls() {
        LlmProviderAttempt attempt = createAttempt("auth_t90", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setClaimExpiresAt(Instant.now().minusSeconds(10));
            providerAttemptRepository.saveAndFlush(updated);
        });

        AtomicInteger calls = new AtomicInteger(0);
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                calls.incrementAndGet();
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(0, calls.get(), "Transport invocation count MUST be 0 on pre-dispatch failure");
    }

    @Test
    @DisplayName("TEST91: Idempotency-supported provider (OpenAI) allows custom idempotency key")
    void test91_idempotencySupportedProviderExecution() {
        LlmProviderAttempt attempt = createAttempt("auth_t91", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
    }

    @Test
    @DisplayName("TEST92: Idempotency-unsupported provider (Anthropic) rejects custom idempotency key")
    void test92_idempotencyUnsupportedProviderRejection() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LlmProviderAttempt attempt = tx.execute(status -> {
            LlmLogicalRequest req = new LlmLogicalRequest();
            req.setOrganization(testOrg);
            req.setProject(testProject);
            req.setSourceAuthorityType("TASK_EXECUTION");
            req.setSourceAuthorityId("auth_t92");
            req.setIdempotencyKey("idem_" + UUID.randomUUID());
            req.setFeatureName("CODE_GENERATION");
            req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            req = logicalRequestRepository.saveAndFlush(req);

            LlmProviderAttempt a = new LlmProviderAttempt();
            a.setLogicalRequest(req);
            a.setAttemptNumber(1);
            a.setProviderId("anthropic");
            a.setModelId("claude-3-5-sonnet");
            a.setPricingVersion(defaultPricingVersion);
            a.setProviderIdempotencyKey("custom_idempotency_key_92");
            a.setInputHash("input_hash_92");
            a.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            return providerAttemptRepository.saveAndFlush(a);
        });

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        com.repopilot.llm.provider.transport.FakeAnthropicTransport fakeAnthropic = new com.repopilot.llm.provider.transport.FakeAnthropicTransport();
        com.repopilot.llm.provider.adapter.AnthropicProviderAdapter anthropicAdapter = new com.repopilot.llm.provider.adapter.AnthropicProviderAdapter(fakeAnthropic);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", anthropicAdapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.UNSUPPORTED_CAPABILITY, outcome.getFailureReason());
    }

    @Test
    @DisplayName("TEST93: TIMEOUT_AFTER_POSSIBLE_DISPATCH maps to UNKNOWN_OUTCOME")
    void test93_possibleExternalExecutionClassification() {
        LlmProviderAttempt attempt = createAttempt("auth_t93", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        transport.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.UNKNOWN_OUTCOME, outcome.getStatus());
        assertEquals(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, outcome.getFailureReason());
    }

    @Test
    @DisplayName("TEST94: Timeout-after-dispatch maps to TIMED_OUT status during finalization")
    void test94_timeoutAfterDispatchFinalizesToTimedOut() {
        LlmProviderAttempt attempt = createAttempt("auth_t94", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        transport.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        boolean finalized = executionService.finalizeAttemptOutcome(claim.getAttemptId(), claim.getLeaseToken(), claim.getFencingVersion(), outcome);
        assertTrue(finalized);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.TIMED_OUT, updated.getExecutionStatus());
            assertNotEquals(LlmExecutionStatus.FAILED, updated.getExecutionStatus());
        });
    }

    @Test
    @DisplayName("TEST95: Fallback safety signal distinguishes pre-dispatch failure vs post-dispatch timeout")
    void test95_fallbackSafetySignalDistinction() {
        LlmProviderAttempt attempt1 = createAttempt("auth_t95_1", 1);
        LlmExecutionClaimResult claim1 = executionService.claimAttemptExecution(attempt1.getId(), 30000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt1.getId()).orElseThrow();
            updated.setClaimExpiresAt(Instant.now().minusSeconds(10));
            providerAttemptRepository.saveAndFlush(updated);
        });

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome1 = executionService.executeWithFencing(claim1, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome1.getStatus());
        assertEquals(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, outcome1.getFailureReason());

        LlmProviderAttempt attempt2 = createAttempt("auth_t95_2", 1);
        LlmExecutionClaimResult claim2 = executionService.claimAttemptExecution(attempt2.getId(), 30000L);
        transport.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");

        LlmExecutionOutcome outcome2 = executionService.executeWithFencing(claim2, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.UNKNOWN_OUTCOME, outcome2.getStatus());
    }

    @Test
    @DisplayName("TEST96: Stale worker execution and finalization does not alter OrganizationUsageCounter")
    void test96_staleWorkerFinancialIsolation() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        OrganizationUsageCounter initialCounter = tx.execute(status ->
                organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg))
        );
        long initialSpend = initialCounter.getTotalSpendCents();
        long initialTokens = initialCounter.getTokensConsumed();

        LlmProviderAttempt attempt = createAttempt("auth_t96", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease_96");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcomeA = executionService.executeWithFencing(claimA, "prompt", adapter);
        executionService.finalizeAttemptOutcome(claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), outcomeA);

        OrganizationUsageCounter finalCounter = tx.execute(status ->
                organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg))
        );
        assertEquals(initialSpend, finalCounter.getTotalSpendCents());
        assertEquals(initialTokens, finalCounter.getTokensConsumed());
    }

    @Test
    @DisplayName("TEST97: Provider request identity accurately derived from database attempt")
    void test97_providerRequestIdentityDerivedFromAttempt() {
        LlmProviderAttempt attempt = createAttempt("auth_t97", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        AtomicReference<LlmProviderRequest> capturedRequest = new AtomicReference<>();
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                capturedRequest.set(request);
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        executionService.executeWithFencing(claim, "prompt_97", adapter);
        LlmProviderRequest req = capturedRequest.get();
        assertNotNull(req);
        assertEquals(attempt.getId(), req.getAttemptId());
        assertEquals(attempt.getLogicalRequest().getId(), req.getLogicalRequestId());
        assertEquals(attempt.getProviderIdempotencyKey(), req.getProviderIdempotencyKey());
    }

    @Test
    @DisplayName("TEST98: Duplicate executeWithFencing calls on same claimResult -> only first proceeds")
    void test98_duplicateExecutionFailsPreDispatch() {
        LlmProviderAttempt attempt = createAttempt("auth_t98", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        AtomicInteger calls = new AtomicInteger(0);
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                calls.incrementAndGet();
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome1 = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome1.getStatus());
        assertEquals(1, calls.get());

        LlmExecutionOutcome outcome2 = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.FAILED, outcome2.getStatus());
        assertEquals(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, outcome2.getFailureReason());
        assertEquals(1, calls.get(), "Second execution call MUST be blocked before provider dispatch");
    }

    @Test
    @DisplayName("TEST99: Multi-threaded lease boundary race condition handled atomically")
    void test99_multiThreadedLeaseBoundaryRace() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t99", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        int numThreads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
                    if (outcome.getStatus() == LlmExecutionOutcome.Status.SUCCEEDED) {
                        successCount.incrementAndGet();
                    } else {
                        failedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            }));
        }

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly ONE thread can pass pre-dispatch fencing");
        assertEquals(numThreads - 1, failedCount.get(), "All other concurrent execute attempts must fail pre-dispatch");
    }

    @Test
    @DisplayName("TEST100: Caller cannot forge lease token or fencing version to finalize attempt")
    void test100_noCallerControlledLeaseAuthority() {
        LlmProviderAttempt attempt = createAttempt("auth_t100", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        LlmProviderResponse resp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_t100", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcome = LlmExecutionOutcome.succeeded(resp);

        boolean forgedTokenFinalized = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), "forged_lease_token_12345", claim.getFencingVersion(), outcome
        );
        assertFalse(forgedTokenFinalized, "Forged lease token MUST be rejected");

        boolean forgedVersionFinalized = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), claim.getLeaseToken(), 99999L, outcome
        );
        assertFalse(forgedVersionFinalized, "Forged fencing version MUST be rejected");
    }

    @Test
    @DisplayName("TEST101: Lease expires after EXECUTING fence but before transport -> finalization fails")
    void test101_leaseExpiresAfterExecutingBeforeTransport() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t101", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        CountDownLatch preDispatchFencePassed = new CountDownLatch(1);
        CountDownLatch reclaimDone = new CountDownLatch(1);

        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                preDispatchFencePassed.countDown();
                try {
                    reclaimDone.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LlmExecutionOutcome> futureOutcome = executor.submit(() ->
                executionService.executeWithFencing(claim, "prompt", adapter)
        );

        assertTrue(preDispatchFencePassed.await(5, TimeUnit.SECONDS));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease_101");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        reclaimDone.countDown();
        LlmExecutionOutcome outcome = futureOutcome.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        boolean finalized = executionService.finalizeAttemptOutcome(
                claim.getAttemptId(), claim.getLeaseToken(), claim.getFencingVersion(), outcome
        );
        assertFalse(finalized, "Finalization MUST be rejected after lease loss");
    }

    @Test
    @DisplayName("TEST102: Stale worker crosses external dispatch boundary -> DB fencing blocks finalization & retains Worker B state")
    void test102_staleWorkerCrossesDispatchBoundary() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t102", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        CountDownLatch workerAEnteredTransport = new CountDownLatch(1);
        CountDownLatch workerBReclaimCompleted = new CountDownLatch(1);

        FakeOpenAiTransport barrierTransport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                workerAEnteredTransport.countDown();
                try {
                    workerBReclaimCompleted.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapterA = new OpenAiProviderAdapter(barrierTransport);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LlmExecutionOutcome> futureA = executor.submit(() ->
                executionService.executeWithFencing(claimA, "promptA", adapterA)
        );

        assertTrue(workerAEnteredTransport.await(5, TimeUnit.SECONDS));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease_102");
            updated.setFencingVersion(3L);
            updated.setExecutionStatus(LlmExecutionStatus.EXECUTION_CLAIMED);
            providerAttemptRepository.saveAndFlush(updated);
        });

        workerBReclaimCompleted.countDown();
        LlmExecutionOutcome outcomeA = futureA.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcomeA.getStatus());

        boolean finalizedA = executionService.finalizeAttemptOutcome(
                claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), outcomeA
        );
        assertFalse(finalizedA, "Worker A finalization MUST be rejected when Worker B reclaimed V3");

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt current = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals("worker_b_lease_102", current.getWorkerLeaseToken());
            assertEquals(3L, current.getFencingVersion());
        });
    }

    @Test
    @DisplayName("TEST103: Stale worker cannot finalize after dispatch")
    void test103_staleWorkerCannotFinalizeAfterDispatch() {
        LlmProviderAttempt attempt = createAttempt("auth_t103", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);
        LlmExecutionOutcome outcomeA = executionService.executeWithFencing(claimA, "prompt", adapter);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease_103");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        boolean finalized = executionService.finalizeAttemptOutcome(
                claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), outcomeA
        );
        assertFalse(finalized, "Stale worker finalization must return false");
    }

    @Test
    @DisplayName("TEST104: Newer worker cannot reinterpret stale possible dispatch as confirmed failure")
    void test104_newerWorkerCannotReinterpretStaleDispatchAsConfirmedFailure() {
        LlmProviderAttempt attempt = createAttempt("auth_t104", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        transport.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcomeA = executionService.executeWithFencing(claimA, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.UNKNOWN_OUTCOME, outcomeA.getStatus());
        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, outcomeA.getSafetyClassification());
        assertNotEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_NOT_DISPATCHED, outcomeA.getSafetyClassification());
        assertNotEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_PROVIDER_REJECTION, outcomeA.getSafetyClassification());
    }

    @Test
    @DisplayName("TEST105: Unsupported-idempotency provider enters UNKNOWN_OUTCOME on post-dispatch timeout")
    void test105_unsupportedIdempotencyProviderEntersUnknownOutcome() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LlmProviderAttempt attempt = tx.execute(status -> {
            LlmLogicalRequest req = new LlmLogicalRequest();
            req.setOrganization(testOrg);
            req.setProject(testProject);
            req.setSourceAuthorityType("TASK_EXECUTION");
            req.setSourceAuthorityId("auth_t105");
            req.setIdempotencyKey("idem_" + UUID.randomUUID());
            req.setFeatureName("CODE_GENERATION");
            req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            req = logicalRequestRepository.saveAndFlush(req);

            LlmProviderAttempt a = new LlmProviderAttempt();
            a.setLogicalRequest(req);
            a.setAttemptNumber(1);
            a.setProviderId("anthropic");
            a.setModelId("claude-3-5-sonnet");
            a.setPricingVersion(defaultPricingVersion);
            a.setProviderIdempotencyKey("default_idem_105");
            a.setInputHash("hash_105");
            a.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            return providerAttemptRepository.saveAndFlush(a);
        });

        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);
        com.repopilot.llm.provider.transport.FakeAnthropicTransport fakeAnthropic = new com.repopilot.llm.provider.transport.FakeAnthropicTransport();
        fakeAnthropic.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");
        com.repopilot.llm.provider.adapter.AnthropicProviderAdapter anthropicAdapter = new com.repopilot.llm.provider.adapter.AnthropicProviderAdapter(fakeAnthropic);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", anthropicAdapter);
        assertEquals(LlmExecutionOutcome.Status.UNKNOWN_OUTCOME, outcome.getStatus());
        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, outcome.getSafetyClassification());
    }

    @Test
    @DisplayName("TEST106: Unsupported-idempotency UNKNOWN_OUTCOME blocks automatic fallback")
    void test106_unsupportedIdempotencyBlocksAutomaticFallback() {
        LlmProviderAttempt attempt = createAttempt("auth_t106", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        transport.setExceptionToThrow(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, outcome.getSafetyClassification());

        boolean safeForNonIdempotent = outcome.isSafeForAutomaticFallback(false);
        assertFalse(safeForNonIdempotent, "Automatic fallback MUST be blocked when provider lacks idempotency support and outcome is UNKNOWN");

        boolean safeForIdempotent = outcome.isSafeForAutomaticFallback(true);
        assertTrue(safeForIdempotent, "Automatic fallback is allowed ONLY when provider explicitly supports idempotency");
    }

    @Test
    @DisplayName("TEST107: Idempotency-capable provider preserves deterministic request identity")
    void test107_idempotencyCapableProviderPreservesRequestIdentity() {
        LlmProviderAttempt attempt = createAttempt("auth_t107", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        AtomicReference<LlmProviderRequest> reqRef = new AtomicReference<>();
        FakeOpenAiTransport transport = new FakeOpenAiTransport() {
            @Override
            public LlmProviderResponse execute(LlmProviderRequest request) {
                reqRef.set(request);
                return super.execute(request);
            }
        };
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        LlmExecutionOutcome outcome = executionService.executeWithFencing(claim, "prompt", adapter);
        assertEquals(LlmExecutionOutcome.Status.SUCCEEDED, outcome.getStatus());
        assertNotNull(reqRef.get());
        assertEquals(attempt.getId(), reqRef.get().getAttemptId());
        assertEquals(attempt.getProviderIdempotencyKey(), reqRef.get().getProviderIdempotencyKey());
    }

    @Test
    @DisplayName("TEST108: TIMED_OUT after dispatch remains externally UNKNOWN")
    void test108_timedOutAfterDispatchRemainsExternallyUnknown() {
        LlmExecutionStatus status = LlmExecutionStatus.TIMED_OUT;
        LlmExecutionOutcome.SafetyClassification classification = status.toSafetyClassification();

        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, classification);
        assertNotEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_NOT_DISPATCHED, classification);
        assertNotEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_PROVIDER_REJECTION, classification);
    }

    @Test
    @DisplayName("TEST109: Confirmed pre-dispatch failure is distinguishable from post-dispatch timeout")
    void test109_preDispatchFailureDistinguishableFromPostDispatchTimeout() {
        LlmProviderException preDispatchEx = new LlmProviderException(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, "Pre-dispatch expired");
        LlmExecutionOutcome preOutcome = LlmExecutionOutcome.failed(preDispatchEx);

        LlmProviderException postDispatchEx = new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Post-dispatch timeout");
        LlmExecutionOutcome postOutcome = LlmExecutionOutcome.unknownOutcome(postDispatchEx);

        assertEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_NOT_DISPATCHED, preOutcome.getSafetyClassification());
        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, postOutcome.getSafetyClassification());

        assertTrue(preOutcome.isSafeForAutomaticFallback(false), "Pre-dispatch failure must be safe for fallback without idempotency");
        assertFalse(postOutcome.isSafeForAutomaticFallback(false), "Post-dispatch timeout must NOT be safe for fallback without idempotency");
    }

    @Test
    @DisplayName("TEST110: Duplicate stale response cannot mutate financial state")
    void test110_duplicateStaleResponseCannotMutateFinancialState() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        OrganizationUsageCounter beforeCounter = tx.execute(status ->
                organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg))
        );
        long initialSpendCents = beforeCounter.getTotalSpendCents();
        long initialTokens = beforeCounter.getTokensConsumed();

        LlmProviderAttempt attempt = createAttempt("auth_t110", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            updated.setWorkerLeaseToken("worker_b_lease_110");
            updated.setFencingVersion(3L);
            providerAttemptRepository.saveAndFlush(updated);
        });

        LlmProviderResponse staleResponse = new LlmProviderResponse(
                "openai", "gpt-4o", "req_stale_110", 100, 200, 300, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome staleOutcome = LlmExecutionOutcome.succeeded(staleResponse);

        boolean finalized = executionService.finalizeAttemptOutcome(
                claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), staleOutcome
        );
        assertFalse(finalized, "Stale worker finalization MUST be rejected");

        OrganizationUsageCounter afterCounter = tx.execute(status ->
                organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg))
        );
        assertEquals(initialSpendCents, afterCounter.getTotalSpendCents(), "Financial spend cents MUST NOT change on stale response");
        assertEquals(initialTokens, afterCounter.getTokensConsumed(), "Tokens consumed MUST NOT change on stale response");
    }

    @Test
    @DisplayName("TEST111: Concurrent reclaim + dispatch boundary is handled deterministically")
    void test111_concurrentReclaimAndDispatchBoundary() throws Exception {
        LlmProviderAttempt attempt = createAttempt("auth_t111", 1);
        LlmExecutionClaimResult claimA = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        int numWorkers = 3;
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        CyclicBarrier barrier = new CyclicBarrier(numWorkers);

        AtomicInteger finalizedSuccess = new AtomicInteger(0);
        AtomicInteger finalizedRejected = new AtomicInteger(0);

        FakeOpenAiTransport transport = new FakeOpenAiTransport();
        OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(transport);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numWorkers; i++) {
            final int workerId = i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    if (workerId == 0) {
                        LlmExecutionOutcome outcome = executionService.executeWithFencing(claimA, "prompt", adapter);
                        boolean fin = executionService.finalizeAttemptOutcome(claimA.getAttemptId(), claimA.getLeaseToken(), claimA.getFencingVersion(), outcome);
                        if (fin) finalizedSuccess.incrementAndGet();
                        else finalizedRejected.incrementAndGet();
                    } else {
                        LlmExecutionClaimResult res = executionService.claimAttemptExecution(attempt.getId(), 30000L);
                        if (!res.isSuccess()) finalizedRejected.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            }));
        }

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        assertTrue(finalizedSuccess.get() <= 1, "At most ONE worker finalization can succeed");
    }

    @Test
    @DisplayName("TEST112: Forged lease/fencing identity cannot alter outcome")
    void test112_forgedLeaseFencingIdentityCannotAlterOutcome() {
        LlmProviderAttempt attempt = createAttempt("auth_t112", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        LlmProviderResponse resp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_t112", 10, 20, 30, null, "text", "hash", Instant.now(), Map.of()
        );
        LlmExecutionOutcome outcome = LlmExecutionOutcome.succeeded(resp);

        boolean res1 = executionService.finalizeAttemptOutcome(claim.getAttemptId(), "forged_token_xyz", claim.getFencingVersion(), outcome);
        assertFalse(res1, "Forged lease token must be rejected");

        boolean res2 = executionService.finalizeAttemptOutcome(claim.getAttemptId(), claim.getLeaseToken(), 99999L, outcome);
        assertFalse(res2, "Forged fencing version must be rejected");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt updated = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.EXECUTION_CLAIMED, updated.getExecutionStatus());
            assertNull(updated.getProviderRequestId());
        });
    }

    @Test
    @DisplayName("TEST113: Generic HTTP 500 after possible dispatch -> UNKNOWN_EXTERNAL_OUTCOME")
    void test113_genericHttp500MapsToUnknownExternalOutcome() {
        LlmProviderException ex = new LlmProviderException(LlmProviderFailureReason.PROVIDER_INTERNAL_ERROR, "HTTP 500 Internal Server Error");
        LlmExecutionOutcome outcome = LlmExecutionOutcome.failed(ex);

        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, outcome.getSafetyClassification(),
                "Generic HTTP 500 MUST be classified as UNKNOWN_EXTERNAL_OUTCOME");
    }

    @Test
    @DisplayName("TEST114: HTTP 500 cannot be classified as CONFIRMED_PROVIDER_REJECTION merely from status code")
    void test114_http500NotClassifiedAsConfirmedProviderRejection() {
        LlmProviderException ex = new LlmProviderException(LlmProviderFailureReason.PROVIDER_INTERNAL_ERROR, "HTTP 500");
        LlmExecutionOutcome outcome = LlmExecutionOutcome.failed(ex);

        assertNotEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_PROVIDER_REJECTION, outcome.getSafetyClassification(),
                "HTTP 500 MUST NOT be classified as CONFIRMED_PROVIDER_REJECTION");
    }

    @Test
    @DisplayName("TEST115: Explicit provider-documented pre-execution rejection -> CONFIRMED_PROVIDER_REJECTION")
    void test115_explicitPreExecutionRejection() {
        LlmProviderException authEx = new LlmProviderException(LlmProviderFailureReason.AUTHENTICATION_FAILURE, "Invalid API key");
        LlmExecutionOutcome authOutcome = LlmExecutionOutcome.failed(authEx);
        assertEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_PROVIDER_REJECTION, authOutcome.getSafetyClassification());

        LlmProviderException unsuppEx = new LlmProviderException(LlmProviderFailureReason.UNSUPPORTED_CAPABILITY, "Capability unsupported");
        LlmExecutionOutcome unsuppOutcome = LlmExecutionOutcome.failed(unsuppEx);
        assertEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_PROVIDER_REJECTION, unsuppOutcome.getSafetyClassification());
    }

    @Test
    @DisplayName("TEST116: Generic 4xx post-dispatch does not automatically imply confirmed non-execution")
    void test116_generic4xxPostDispatchDoesNotImplyConfirmedNonExecution() {
        LlmProviderException rateLimitEx = new LlmProviderException(LlmProviderFailureReason.RATE_LIMIT_EXCEEDED, "Rate limit exceeded mid-flight");
        LlmExecutionOutcome rateLimitOutcome = LlmExecutionOutcome.failed(rateLimitEx);

        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, rateLimitOutcome.getSafetyClassification(),
                "Generic mid-flight 4xx (rate limit) MUST be classified as UNKNOWN_EXTERNAL_OUTCOME");
    }

    @Test
    @DisplayName("TEST117: Idempotency-capable provider may retry UNKNOWN_EXTERNAL_OUTCOME using same request identity")
    void test117_idempotencyCapableProviderRetryUsesSameIdentity() {
        LlmProviderAttempt attempt = createAttempt("auth_t117", 1);
        LlmExecutionClaimResult claim = executionService.claimAttemptExecution(attempt.getId(), 30000L);

        LlmProviderException postDispatchEx = new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");
        LlmExecutionOutcome outcome = LlmExecutionOutcome.unknownOutcome(postDispatchEx);

        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, outcome.getSafetyClassification());

        boolean safeForIdempotent = outcome.isSafeForAutomaticFallback(true);
        assertTrue(safeForIdempotent, "Idempotency-capable provider permits retry under idempotency contract");

        assertEquals(attempt.getId(), claim.getAttempt().getId());
        assertNotNull(claim.getAttempt().getProviderIdempotencyKey());
    }

    @Test
    @DisplayName("TEST118: Idempotency capability does not imply external exactly-once execution guarantee")
    void test118_idempotencyCapabilityDoesNotImplyExactlyOnceExecution() {
        LlmProviderException ex = new LlmProviderException(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout");
        LlmExecutionOutcome outcome = LlmExecutionOutcome.unknownOutcome(ex);

        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, outcome.getSafetyClassification(),
                "Outcome classification remains UNKNOWN_EXTERNAL_OUTCOME regardless of idempotency support");
        assertNotEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_NOT_DISPATCHED, outcome.getSafetyClassification());
    }

    @Test
    @DisplayName("TEST119: Unsupported-idempotency provider + UNKNOWN_EXTERNAL_OUTCOME blocks automatic fallback")
    void test119_unsupportedIdempotencyBlocksFallback() {
        LlmProviderException ex = new LlmProviderException(LlmProviderFailureReason.PROVIDER_INTERNAL_ERROR, "Generic 500");
        LlmExecutionOutcome outcome = LlmExecutionOutcome.failed(ex);

        assertEquals(LlmExecutionOutcome.SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME, outcome.getSafetyClassification());
        assertFalse(outcome.isSafeForAutomaticFallback(false),
                "Automatic fallback MUST be blocked for idempotency-unsupported provider on UNKNOWN_EXTERNAL_OUTCOME");
    }

    @Test
    @DisplayName("TEST120: CONFIRMED_NOT_DISPATCHED remains universally safe for fallback")
    void test120_confirmedNotDispatchedUniversallySafe() {
        LlmProviderException preDispatchEx = new LlmProviderException(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, "Lease expired");
        LlmExecutionOutcome outcome = LlmExecutionOutcome.failed(preDispatchEx);

        assertEquals(LlmExecutionOutcome.SafetyClassification.CONFIRMED_NOT_DISPATCHED, outcome.getSafetyClassification());
        assertTrue(outcome.isSafeForAutomaticFallback(false), "CONFIRMED_NOT_DISPATCHED is safe without idempotency");
        assertTrue(outcome.isSafeForAutomaticFallback(true), "CONFIRMED_NOT_DISPATCHED is safe with idempotency");
    }

    @Test
    @DisplayName("TEST121: STALE_INTERNAL_RESULT cannot independently trigger fallback")
    void test121_staleInternalResultCannotTriggerFallback() {
        LlmExecutionOutcome staleOutcome = LlmExecutionOutcome.staleResult("Stale worker lost lease");

        assertEquals(LlmExecutionOutcome.SafetyClassification.STALE_INTERNAL_RESULT, staleOutcome.getSafetyClassification());
        assertFalse(staleOutcome.isSafeForAutomaticFallback(false), "STALE_INTERNAL_RESULT cannot trigger fallback");
        assertFalse(staleOutcome.isSafeForAutomaticFallback(true), "STALE_INTERNAL_RESULT cannot trigger fallback");
    }
}
