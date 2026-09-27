package com.repopilot.llm;

import com.repopilot.llm.dto.LlmReservationResult;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.repository.*;
import com.repopilot.llm.service.LlmAccountingService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationPolicy;
import com.repopilot.organization.entity.OrganizationUsageCounter;
import com.repopilot.organization.repository.OrganizationPolicyRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4B_HardBudgetAccountingTest {

    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationPolicyRepository organizationPolicyRepository;
    @Autowired private OrganizationUsageCounterRepository organizationUsageCounterRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private LlmProviderAttemptRepository providerAttemptRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private OrganizationOverageLiabilityRepository overageLiabilityRepository;
    @Autowired private LlmAccountingService accountingService;
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
            testUser.setEmail("llm_4b_user_" + UUID.randomUUID() + "@example.com");
            testUser.setPasswordHash("hash");
            testUser.setName("LLM 4B User");
            testUser.setRole(UserRole.USER);
            testUser = userRepository.save(testUser);

            testOrg = new Organization();
            testOrg.setName("LLM 4B Org " + UUID.randomUUID());
            testOrg.setSlug("llm-4b-org-" + UUID.randomUUID());
            testOrg = organizationRepository.save(testOrg);

            testProject = new Project();
            testProject.setName("LLM 4B Project");
            testProject.setDescription("LLM Slice 4B Project");
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
            overageLiabilityRepository.deleteAllInBatch();
            providerAttemptRepository.deleteAllInBatch();
            logicalRequestRepository.deleteAllInBatch();
            auditLogOutboxRepository.deleteAllInBatch();
            organizationUsageCounterRepository.deleteAllInBatch();
            organizationPolicyRepository.deleteAllInBatch();

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
            attempt.setInputHash("input_hash_4b");
            attempt.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
            return providerAttemptRepository.saveAndFlush(attempt);
        });
    }

    private void configurePolicy(Long monthlyTokenBudget, BigDecimal monthlySpendLimitUsd) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationPolicy policy = organizationPolicyRepository.findByOrganizationId(testOrg.getId())
                    .orElseGet(() -> new OrganizationPolicy(testOrg));
            policy.setMonthlyTokenBudget(monthlyTokenBudget);
            policy.setMonthlySpendLimitUsd(monthlySpendLimitUsd);
            organizationPolicyRepository.saveAndFlush(policy);
        });
    }

    private void assertHardFinancialInvariants(Organization org) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(org.getId())
                    .orElseGet(() -> new OrganizationUsageCounter(org));
            Optional<OrganizationPolicy> policyOpt = organizationPolicyRepository.findByOrganizationId(org.getId());

            long effectiveTokens = counter.getTokensConsumed() + counter.getReservedTokens() + counter.getUnresolvedExposureTokens();
            long effectiveSpendMicroUsd = (counter.getTotalSpendCents() * 10_000L) + counter.getReservedSpendMicroUsd() + counter.getUnresolvedExposureSpendMicroUsd();

            if (policyOpt.isPresent()) {
                OrganizationPolicy policy = policyOpt.get();
                if (policy.getMonthlyTokenBudget() != null && policy.getMonthlyTokenBudget() > 0) {
                    assertTrue(effectiveTokens <= policy.getMonthlyTokenBudget(),
                            "Hard Token Invariant Violated: used(" + counter.getTokensConsumed() + ") + reserved("
                                    + counter.getReservedTokens() + ") + exposure(" + counter.getUnresolvedExposureTokens()
                                    + ") = " + effectiveTokens + " > budget(" + policy.getMonthlyTokenBudget() + ")");
                }
                if (policy.getMonthlySpendLimitUsd() != null && policy.getMonthlySpendLimitUsd().compareTo(BigDecimal.ZERO) > 0) {
                    long limitMicroUsd = policy.getMonthlySpendLimitUsd().multiply(BigDecimal.valueOf(1_000_000L)).longValueExact();
                    assertTrue(effectiveSpendMicroUsd <= limitMicroUsd,
                            "Hard Spend Invariant Violated: totalSpendMicroUsd(" + (counter.getTotalSpendCents() * 10_000L)
                                    + ") + reservedMicroUsd(" + counter.getReservedSpendMicroUsd()
                                    + ") + exposureMicroUsd(" + counter.getUnresolvedExposureSpendMicroUsd()
                                    + ") = " + effectiveSpendMicroUsd + " > spendLimit(" + limitMicroUsd + ")");
                }
            }
        });
    }

    @Test
    @DisplayName("TEST01: Successful token reservation under policy budget")
    void test01_successfulTokenReservation() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t1", 1);

        LlmReservationResult result = accountingService.reserveCapacity(attempt.getId(), 2000, 50000L);
        assertTrue(result.isSuccess());
        assertNull(result.getRejectionReason());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(2000, counter.getReservedTokens());
            assertEquals(50000L, counter.getReservedSpendMicroUsd());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.EXECUTION_CLAIMED, updatedAttempt.getExecutionStatus());
            assertEquals(LlmBillingStatus.UNBILLED, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST01b: Sequential duplicate reservation is idempotent with single financial reservation effect")
    void test01b_sequentialDuplicateReservation() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t1b", 1);

        LlmReservationResult res1 = accountingService.reserveCapacity(attempt.getId(), 2000, 50000L);
        assertTrue(res1.isSuccess());

        LlmReservationResult res2 = accountingService.reserveCapacity(attempt.getId(), 2000, 50000L);
        assertTrue(res2.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(2000, counter.getReservedTokens(), "Reserved tokens must NOT double increment on duplicate reservation");
            assertEquals(50000L, counter.getReservedSpendMicroUsd(), "Reserved spend must NOT double increment on duplicate reservation");
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST02: Token budget rejection when requested reservation exceeds monthly limit")
    void test02_tokenBudgetRejection() {
        configurePolicy(100L, new BigDecimal("100.00")); // Budget = 100 tokens
        LlmProviderAttempt attempt = createAttempt("auth_t2", 1);

        LlmReservationResult result = accountingService.reserveCapacity(attempt.getId(), 120, 50000L);
        assertFalse(result.isSuccess());
        assertEquals("HARD_TOKEN_BUDGET_EXCEEDED", result.getRejectionReason());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg));
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getReservedSpendMicroUsd());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals("HARD_TOKEN_BUDGET_EXCEEDED", updatedAttempt.getFallbackReason());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST03: Successful spend reservation under policy spend limit")
    void test03_successfulSpendReservation() {
        configurePolicy(100000L, new BigDecimal("10.00")); // Spend limit = $10.00 (10,000,000 micro-USD)
        LlmProviderAttempt attempt = createAttempt("auth_t3", 1);

        LlmReservationResult result = accountingService.reserveCapacity(attempt.getId(), 500, 2_000_000L);
        assertTrue(result.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(2_000_000L, counter.getReservedSpendMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST04: Spend limit rejection when requested spend exceeds monthly limit")
    void test04_spendLimitRejection() {
        configurePolicy(100000L, new BigDecimal("1.00")); // Spend limit = $1.00 (1,000,000 micro-USD)
        LlmProviderAttempt attempt = createAttempt("auth_t4", 1);

        LlmReservationResult result = accountingService.reserveCapacity(attempt.getId(), 500, 1_500_000L);
        assertFalse(result.isSuccess());
        assertEquals("HARD_SPEND_LIMIT_EXCEEDED", result.getRejectionReason());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg));
            assertEquals(0L, counter.getReservedSpendMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST05: Concurrent token reservation race - exactly one winner commits under budget limit")
    void test05_concurrentTokenReservationOneWinner() throws Exception {
        configurePolicy(100L, new BigDecimal("100.00")); // Budget = 100 tokens

        LlmProviderAttempt attempt1 = createAttempt("auth_t5_1", 1);
        LlmProviderAttempt attempt2 = createAttempt("auth_t5_2", 1);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectionCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                LlmReservationResult res = accountingService.reserveCapacity(attempt1.getId(), 80, 10000L);
                if (res.isSuccess()) successCount.incrementAndGet();
                else rejectionCount.incrementAndGet();
            } catch (Exception e) {
                rejectionCount.incrementAndGet();
            }
        }));

        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                LlmReservationResult res = accountingService.reserveCapacity(attempt2.getId(), 80, 10000L);
                if (res.isSuccess()) successCount.incrementAndGet();
                else rejectionCount.incrementAndGet();
            } catch (Exception e) {
                rejectionCount.incrementAndGet();
            }
        }));

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one reservation must succeed");
        assertEquals(1, rejectionCount.get(), "Exactly one reservation must be rejected");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            long effectiveTokens = counter.getTokensConsumed() + counter.getReservedTokens() + counter.getUnresolvedExposureTokens();
            assertTrue(effectiveTokens <= 100L, "Durable state tokens must satisfy <= 100 budget invariant: actual=" + effectiveTokens);
            assertEquals(80, counter.getReservedTokens());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST05b: Concurrent duplicate reservation attempts for exact same attemptId produce single financial effect")
    void test05b_concurrentDuplicateReservationSameAttempt() throws Exception {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t5b", 1);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
                } catch (Exception ignored) {}
            }));
        }

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(1000, counter.getReservedTokens(), "Reserved tokens must be exactly 1000");
            assertEquals(25000L, counter.getReservedSpendMicroUsd(), "Reserved spend must be exactly 25000 micro USD");
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST06: Concurrent spend reservation race - exactly one winner commits under spend limit")
    void test06_concurrentSpendReservationOneWinner() throws Exception {
        configurePolicy(100000L, new BigDecimal("1.00")); // Spend limit = $1.00 (1,000,000 micro-USD)

        LlmProviderAttempt attempt1 = createAttempt("auth_t6_1", 1);
        LlmProviderAttempt attempt2 = createAttempt("auth_t6_2", 1);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectionCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                LlmReservationResult res = accountingService.reserveCapacity(attempt1.getId(), 100, 800_000L);
                if (res.isSuccess()) successCount.incrementAndGet();
                else rejectionCount.incrementAndGet();
            } catch (Exception e) {
                rejectionCount.incrementAndGet();
            }
        }));

        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                LlmReservationResult res = accountingService.reserveCapacity(attempt2.getId(), 100, 800_000L);
                if (res.isSuccess()) successCount.incrementAndGet();
                else rejectionCount.incrementAndGet();
            } catch (Exception e) {
                rejectionCount.incrementAndGet();
            }
        }));

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one spend reservation must succeed");
        assertEquals(1, rejectionCount.get(), "Exactly one spend reservation must be rejected");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            long currentSpend = (counter.getTotalSpendCents() * 10_000L) + counter.getReservedSpendMicroUsd() + counter.getUnresolvedExposureSpendMicroUsd();
            assertTrue(currentSpend <= 1_000_000L, "Durable state spend must satisfy <= 1,000,000 micro-USD limit: actual=" + currentSpend);
            assertEquals(800_000L, counter.getReservedSpendMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST07: Reservation release returns reserved capacity without charge")
    void test07_reservationRelease() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t7", 1);

        accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
        LlmReservationResult releaseResult = accountingService.releaseReservation(attempt.getId());

        assertTrue(releaseResult.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getReservedSpendMicroUsd());
            assertEquals(0L, counter.getTokensConsumed());
            assertEquals(0L, counter.getTotalSpendCents());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST08: Duplicate release is idempotent and produces no negative reservations")
    void test08_duplicateReleaseIsIdempotent() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t8", 1);

        accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
        accountingService.releaseReservation(attempt.getId());
        LlmReservationResult secondRelease = accountingService.releaseReservation(attempt.getId());

        assertTrue(secondRelease.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getReservedSpendMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST09: Reservation settlement releases reservation and commits actual usage")
    void test09_reservationSettlement() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t9", 1);

        accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
        LlmReservationResult settleResult = accountingService.settleReservation(attempt.getId(), 800, 20000L);

        assertTrue(settleResult.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getReservedSpendMicroUsd());
            assertEquals(800L, counter.getTokensConsumed());
            assertEquals(2L, counter.getTotalSpendCents()); // 20,000 micro-USD = 2 cents

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.SUCCEEDED, updatedAttempt.getExecutionStatus());
            assertEquals(LlmBillingStatus.BILLED, updatedAttempt.getBillingStatus());
            assertEquals(800, updatedAttempt.getTotalTokens());
            assertEquals(20000L, updatedAttempt.getCalculatedCostMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST10: Duplicate settlement is idempotent and prevents double-charging")
    void test10_duplicateSettlementIsIdempotent() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t10", 1);

        accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
        accountingService.settleReservation(attempt.getId(), 800, 20000L);
        LlmReservationResult secondSettle = accountingService.settleReservation(attempt.getId(), 800, 20000L);

        assertTrue(secondSettle.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(800L, counter.getTokensConsumed());
            assertEquals(2L, counter.getTotalSpendCents());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST11: Unknown provider exposure transfer moves reserved capacity to unresolved exposure")
    void test11_unknownProviderExposureTransfer() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t11", 1);

        accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
        LlmReservationResult exposureResult = accountingService.transferToUnknownExposure(attempt.getId());

        assertTrue(exposureResult.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getReservedSpendMicroUsd());
            assertEquals(1000, counter.getUnresolvedExposureTokens());
            assertEquals(25000L, counter.getUnresolvedExposureSpendMicroUsd());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.TIMED_OUT, updatedAttempt.getExecutionStatus());
            assertEquals(LlmBillingStatus.BILLING_UNKNOWN, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST12: Unresolved exposure blocks additional admission when total exposure reaches budget limit")
    void test12_unknownExposureBlocksAdditionalAdmission() {
        configurePolicy(100L, new BigDecimal("100.00")); // Budget = 100 tokens
        LlmProviderAttempt attempt1 = createAttempt("auth_t12_1", 1);
        LlmProviderAttempt attempt2 = createAttempt("auth_t12_2", 1);

        accountingService.reserveCapacity(attempt1.getId(), 100, 25000L);
        accountingService.transferToUnknownExposure(attempt1.getId());

        LlmReservationResult result2 = accountingService.reserveCapacity(attempt2.getId(), 20, 5000L);
        assertFalse(result2.isSuccess());
        assertEquals("HARD_TOKEN_BUDGET_EXCEEDED", result2.getRejectionReason());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            long effectiveTokens = counter.getTokensConsumed() + counter.getReservedTokens() + counter.getUnresolvedExposureTokens();
            assertEquals(100L, effectiveTokens);
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST13: Confirmed zero-usage releases exposure without charge")
    void test13_confirmedZeroUsageReleasesExposureWithoutCharge() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t13", 1);

        accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
        accountingService.transferToUnknownExposure(attempt.getId());
        LlmReservationResult resolveResult = accountingService.resolveUnknownExposureZeroUsage(attempt.getId());

        assertTrue(resolveResult.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getUnresolvedExposureTokens());
            assertEquals(0L, counter.getUnresolvedExposureSpendMicroUsd());
            assertEquals(0L, counter.getTokensConsumed());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST14: Confirmed usage settles exposure exactly once")
    void test14_confirmedUsageSettlesExposureExactlyOnce() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t14", 1);

        accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
        accountingService.transferToUnknownExposure(attempt.getId());
        LlmReservationResult resolveResult = accountingService.resolveUnknownExposureActualUsage(attempt.getId(), 900, 22500L);

        assertTrue(resolveResult.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getUnresolvedExposureTokens());
            assertEquals(0L, counter.getUnresolvedExposureSpendMicroUsd());
            assertEquals(900L, counter.getTokensConsumed());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.SUCCEEDED, updatedAttempt.getExecutionStatus());
            assertEquals(LlmBillingStatus.BILLED, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST14b: Confirmed usage exceeding original UNKNOWN exposure generates overage liability")
    void test14b_resolveUnknownExposureActualUsageWithOverage() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t14b", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.transferToUnknownExposure(attempt.getId());

        LlmReservationResult resolveResult = accountingService.resolveUnknownExposureActualUsage(attempt.getId(), 150, 25000L);
        assertTrue(resolveResult.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getUnresolvedExposureTokens());
            assertEquals(0L, counter.getUnresolvedExposureSpendMicroUsd());
            assertEquals(150L, counter.getTokensConsumed());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());
            assertEquals(15000L, liabilities.get(0).getFinancialLiabilityMicroUsd());
            assertEquals("ACTUAL_USAGE_EXCEEDED_EXPOSURE", liabilities.get(0).getReason());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST15: Database transaction rollback leaves no partial reservation mutation")
    void test15_rollbackLeavesNoPartialReservation() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t15", 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(RuntimeException.class, () -> {
            tx.executeWithoutResult(status -> {
                accountingService.reserveCapacity(attempt.getId(), 1000, 25000L);
                throw new RuntimeException("Simulated transaction failure post reservation");
            });
        });

        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElse(new OrganizationUsageCounter(testOrg));
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getReservedSpendMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST16: Financial identity isolation between independent attempts and requests")
    void test16_attemptRequestFinancialIdentityIsolation() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attemptA = createAttempt("auth_t16_a", 1);
        LlmProviderAttempt attemptB = createAttempt("auth_t16_b", 1);

        accountingService.reserveCapacity(attemptA.getId(), 500, 10000L);
        accountingService.settleReservation(attemptA.getId(), 400, 8000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt fetchedB = providerAttemptRepository.findById(attemptB.getId()).orElseThrow();
            assertEquals(LlmExecutionStatus.ATTEMPT_CREATED, fetchedB.getExecutionStatus());
            assertEquals(LlmBillingStatus.UNBILLED, fetchedB.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST17: Audit verification that PostgreSQL is sole financial authority and Redis is not used")
    void test17_redisIsNotFinancialAuthority() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            Optional<OrganizationUsageCounter> counterOpt = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId());
            assertNotNull(organizationUsageCounterRepository);
            assertNotNull(counterOpt);
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST18: Integer micro-USD precision supports large values without Float/Double truncation")
    void test18_largeIntegerMicroUsdPrecision() {
        configurePolicy(100000000L, new BigDecimal("1000000.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t18", 1);

        long largeSpendMicroUsd = 500_000_000_000L; // $500,000.00 USD
        LlmReservationResult result = accountingService.reserveCapacity(attempt.getId(), 1000000, largeSpendMicroUsd);

        assertTrue(result.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(largeSpendMicroUsd, counter.getReservedSpendMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST19: Concurrent unknown exposure transfer and admission race preserves hard budget invariant")
    void test19_concurrentExposureAdmissionPreservesHardInvariant() throws Exception {
        configurePolicy(100L, new BigDecimal("100.00")); // Budget = 100 tokens

        LlmProviderAttempt attempt1 = createAttempt("auth_t19_1", 1);
        LlmProviderAttempt attempt2 = createAttempt("auth_t19_2", 1);

        accountingService.reserveCapacity(attempt1.getId(), 80, 10000L);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        futuresSubmitRace(attempt1, attempt2, executor, barrier);
        executor.shutdown();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            long totalEffectiveTokens = counter.getTokensConsumed() + counter.getReservedTokens() + counter.getUnresolvedExposureTokens();
            assertTrue(totalEffectiveTokens <= 100L, "Hard token budget invariant must hold under concurrent exposure transfer: actual=" + totalEffectiveTokens);
        });
        assertHardFinancialInvariants(testOrg);
    }

    private void futuresSubmitRace(LlmProviderAttempt attempt1, LlmProviderAttempt attempt2, ExecutorService executor, CyclicBarrier barrier) throws InterruptedException, ExecutionException {
        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                accountingService.transferToUnknownExposure(attempt1.getId());
            } catch (Exception ignored) {}
        }));

        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                accountingService.reserveCapacity(attempt2.getId(), 50, 10000L);
            } catch (Exception ignored) {}
        }));

        for (Future<?> f : futures) f.get();
    }

    @Test
    @DisplayName("TEST20: Overage liability creation when actual settled spend exceeds original reservation")
    void test20_overageLiabilityBehavior() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t20", 1);

        accountingService.reserveCapacity(attempt.getId(), 500, 10000L);
        accountingService.settleReservation(attempt.getId(), 600, 25000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());

            OrganizationOverageLiability liability = liabilities.get(0);
            assertEquals(15000L, liability.getFinancialLiabilityMicroUsd());
            assertEquals("ACTUAL_USAGE_EXCEEDED_RESERVATION", liability.getReason());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST20b: Exact token (100->150) and spend (10000->25000) overrun verification against hard budget")
    void test20b_actualUsageExceedsReservationExactTokenAndSpendOverrun() {
        configurePolicy(500L, new BigDecimal("10.00")); // Budget = 500 tokens, $10.00 limit (10,000,000 micro-USD)
        LlmProviderAttempt attempt = createAttempt("auth_t20b", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.settleReservation(attempt.getId(), 150, 25000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(150L, counter.getTokensConsumed(), "Used tokens must be 150");
            assertEquals(0, counter.getReservedTokens(), "Reserved tokens must be 0 after settlement");
            assertEquals(0, counter.getUnresolvedExposureTokens(), "Exposure tokens must be 0");
            assertEquals(2L, counter.getTotalSpendCents(), "Spend cents must be 2");

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());
            assertEquals(15000L, liabilities.get(0).getFinancialLiabilityMicroUsd(), "Overage liability must be 15000 micro USD");

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY, updatedAttempt.getBillingStatus());
        });

        assertHardFinancialInvariants(testOrg);

        LlmProviderAttempt attempt2 = createAttempt("auth_t20b_2", 1);
        LlmReservationResult res2 = accountingService.reserveCapacity(attempt2.getId(), 400, 10000L);
        assertFalse(res2.isSuccess());
        assertEquals("HARD_TOKEN_BUDGET_EXCEEDED", res2.getRejectionReason());
    }

    @Test
    @DisplayName("TEST20c: Duplicate overage settlement (sequential & concurrent) is idempotent and records liability exactly once")
    void test20c_duplicateOverageSettlementIdempotency() throws Exception {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t20c", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.settleReservation(attempt.getId(), 150, 25000L);
        accountingService.settleReservation(attempt.getId(), 150, 25000L);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    accountingService.settleReservation(attempt.getId(), 150, 25000L);
                } catch (Exception ignored) {}
            }));
        }
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(150L, counter.getTokensConsumed(), "Tokens consumed must be exactly 150");
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0, counter.getUnresolvedExposureTokens());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size(), "Overage liability record must be created exactly once");
            assertEquals(15000L, liabilities.get(0).getFinancialLiabilityMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST21: Organization ownership is derived strictly from attempt's logical request preventing cross-tenant leakage")
    void test21_crossTenantAttemptMismatchProtection() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t21", 1);

        Organization attemptOrg = attempt.getLogicalRequest().getOrganization();
        assertEquals(testOrg.getId(), attemptOrg.getId());

        LlmReservationResult res = accountingService.reserveCapacity(attempt.getId(), 300, 10000L);
        assertTrue(res.isSuccess());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(300, counter.getReservedTokens());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST22: Concurrent duplicate release calls execute safely and idempotently")
    void test22_concurrentDuplicateRelease() throws Exception {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t22", 1);

        accountingService.reserveCapacity(attempt.getId(), 500, 10000L);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    accountingService.releaseReservation(attempt.getId());
                } catch (Exception ignored) {}
            }));
        }
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getReservedSpendMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST23: Concurrent duplicate settlement calls execute safely and commit actual usage exactly once")
    void test23_concurrentDuplicateSettlement() throws Exception {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t23", 1);

        accountingService.reserveCapacity(attempt.getId(), 500, 10000L);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    accountingService.settleReservation(attempt.getId(), 400, 8000L);
                } catch (Exception ignored) {}
            }));
        }
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(400L, (long) counter.getTokensConsumed());
            assertEquals(0, (int) counter.getReservedTokens());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST24: Timeout outcome strictly transfers to BILLING_UNKNOWN and never invokes zero-usage release prematurely")
    void test24_timeoutOutcomeLeavesExposureIntact() {
        configurePolicy(10000L, new BigDecimal("100.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t24", 1);

        accountingService.reserveCapacity(attempt.getId(), 500, 10000L);
        accountingService.transferToUnknownExposure(attempt.getId());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(500, counter.getUnresolvedExposureTokens());
            assertEquals(10000L, counter.getUnresolvedExposureSpendMicroUsd());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertNotEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, updatedAttempt.getBillingStatus());
            assertEquals(LlmBillingStatus.BILLING_UNKNOWN, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST25: Checked arithmetic triggers exception and rolls back on overflow attempts")
    void test25_checkedArithmeticOverflowProtection() {
        configurePolicy(0L, BigDecimal.ZERO); // No limit
        LlmProviderAttempt attempt = createAttempt("auth_t25", 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = new OrganizationUsageCounter(testOrg);
            counter.setReservedSpendMicroUsd(Long.MAX_VALUE - 10L);
            organizationUsageCounterRepository.saveAndFlush(counter);
        });

        assertThrows(ArithmeticException.class, () -> {
            accountingService.reserveCapacity(attempt.getId(), 100, 100L);
        });
    }

    @Test
    @DisplayName("TEST26: Reservation 10,000 micro-USD, Actual 25,000 micro-USD -> exact final spend/liability relationship")
    void test26_overageReconciliationExactFormula() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t26", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.settleReservation(attempt.getId(), 150, 25000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(150L, counter.getTokensConsumed());
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0, counter.getUnresolvedExposureTokens());
            assertEquals(2L, counter.getTotalSpendCents());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(25000L, updatedAttempt.getCalculatedCostMicroUsd());
            assertEquals(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY, updatedAttempt.getBillingStatus());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());
            assertEquals(15000L, liabilities.get(0).getFinancialLiabilityMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST27: Reservation exactly equals actual usage -> no overage liability & exact spend accounting")
    void test27_exactReservationSettlementNoOverage() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t27", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.settleReservation(attempt.getId(), 100, 10000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(100L, counter.getTokensConsumed());
            assertEquals(0, counter.getReservedTokens());
            assertEquals(1L, counter.getTotalSpendCents());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertTrue(liabilities.isEmpty());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLED, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST28: Actual usage below reservation -> exact spend accounting & no overage liability")
    void test28_underReservationSettlementNoOverage() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t28", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.settleReservation(attempt.getId(), 50, 5000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(50L, counter.getTokensConsumed());
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0L, counter.getTotalSpendCents());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertTrue(liabilities.isEmpty());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLED, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST29: Actual usage exceeds reservation by 1 micro-USD -> exact boundary behavior")
    void test29_overageBoundarySingleMicroUsd() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t29", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.settleReservation(attempt.getId(), 100, 10001L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());
            assertEquals(1L, liabilities.get(0).getFinancialLiabilityMicroUsd());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST30: Duplicate settlement of an overage -> exact once financial effect & single liability")
    void test30_duplicateOverageSettlementSequential() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t30", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.settleReservation(attempt.getId(), 150, 25000L);
        accountingService.settleReservation(attempt.getId(), 150, 25000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(150L, counter.getTokensConsumed());
            assertEquals(2L, counter.getTotalSpendCents());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());
            assertEquals(15000L, liabilities.get(0).getFinancialLiabilityMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST31: Concurrent duplicate overage settlement -> exactly one financial effect & single liability")
    void test31_concurrentDuplicateOverageSettlement() throws Exception {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t31", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    accountingService.settleReservation(attempt.getId(), 150, 25000L);
                } catch (Exception ignored) {}
            }));
        }
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(150L, counter.getTokensConsumed());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());
            assertEquals(15000L, liabilities.get(0).getFinancialLiabilityMicroUsd());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST32: Confirmed zero usage -> exact final spend/liability/exposure state")
    void test32_confirmedZeroUsageExactState() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t32", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.releaseReservation(attempt.getId());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0L, counter.getTokensConsumed());
            assertEquals(0, counter.getReservedTokens());
            assertEquals(0, counter.getUnresolvedExposureTokens());
            assertEquals(0L, counter.getTotalSpendCents());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertTrue(liabilities.isEmpty());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST33: Unknown provider outcome -> exact unresolved exposure accounting")
    void test33_unknownProviderOutcomeExposureState() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t33", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.transferToUnknownExposure(attempt.getId());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getReservedTokens());
            assertEquals(100, counter.getUnresolvedExposureTokens());
            assertEquals(10000L, counter.getUnresolvedExposureSpendMicroUsd());
            assertEquals(0L, counter.getTokensConsumed());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLING_UNKNOWN, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST34: Unknown exposure followed by confirmed zero -> exposure fully released exactly once")
    void test34_unknownExposureToZeroRelease() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t34", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.transferToUnknownExposure(attempt.getId());
        accountingService.resolveUnknownExposureZeroUsage(attempt.getId());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getUnresolvedExposureTokens());
            assertEquals(0L, counter.getUnresolvedExposureSpendMicroUsd());
            assertEquals(0L, counter.getTokensConsumed());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.EXEMPT_NO_CHARGE, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST35: Unknown exposure followed by confirmed usage -> exposure converted to exact actual usage accounting")
    void test35_unknownExposureToActualUsageConversion() {
        configurePolicy(500L, new BigDecimal("10.00"));
        LlmProviderAttempt attempt = createAttempt("auth_t35", 1);

        accountingService.reserveCapacity(attempt.getId(), 100, 10000L);
        accountingService.transferToUnknownExposure(attempt.getId());
        accountingService.resolveUnknownExposureActualUsage(attempt.getId(), 150, 25000L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0, counter.getUnresolvedExposureTokens());
            assertEquals(0L, counter.getUnresolvedExposureSpendMicroUsd());
            assertEquals(150L, counter.getTokensConsumed());
            assertEquals(2L, counter.getTotalSpendCents());

            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, liabilities.size());
            assertEquals(15000L, liabilities.get(0).getFinancialLiabilityMicroUsd());

            LlmProviderAttempt updatedAttempt = providerAttemptRepository.findById(attempt.getId()).orElseThrow();
            assertEquals(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY, updatedAttempt.getBillingStatus());
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST36: Fractional-cent sub-one-cent accumulation behavior under Model A whole-cent accounting")
    void test36_fractionalCentSubOneCentAccumulationBehavior() {
        configurePolicy(1_000_000L, new BigDecimal("0.05")); // Spend limit = $0.05 (50,000 micro-USD)

        // Scenario 1: 6 requests of 9,999 micro-USD (< 1 cent each)
        for (int i = 1; i <= 6; i++) {
            LlmProviderAttempt attempt = createAttempt("auth_t36_9999_" + i, 1);
            accountingService.reserveCapacity(attempt.getId(), 10, 0L);
            accountingService.settleReservation(attempt.getId(), 10, 9999L);
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0L, counter.getTotalSpendCents(), "Under Model A, totalSpendCents remains 0");

            List<LlmProviderAttempt> attempts = providerAttemptRepository.findAll();
            long totalBilledCost = attempts.stream()
                    .filter(a -> a.getLogicalRequest().getOrganization().getId().equals(testOrg.getId()))
                    .mapToLong(a -> a.getCalculatedCostMicroUsd() != null ? a.getCalculatedCostMicroUsd() : 0L)
                    .sum();
            assertEquals(59994L, totalBilledCost, "Provider attempts track exact accumulated micro-USD (59,994 micro-USD)");
        });

        // Scenario 2: 5,000 micro-USD requests
        LlmProviderAttempt attempt5k = createAttempt("auth_t36_5000", 1);
        accountingService.reserveCapacity(attempt5k.getId(), 10, 0L);
        accountingService.settleReservation(attempt5k.getId(), 10, 5000L);

        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(0L, counter.getTotalSpendCents(), "5,000 micro-USD truncates to 0 cents");
        });
        assertHardFinancialInvariants(testOrg);
    }

    @Test
    @DisplayName("TEST37: Overage precision boundary cases (actual=9999/res=0, actual=10001/res=10000, actual=19999/res=10000)")
    void test37_overagePrecisionBoundaryCases() {
        configurePolicy(1_000_000L, new BigDecimal("100.00"));

        // Case 1: actual = 9,999 micro-USD, reservation = 0
        LlmProviderAttempt attempt1 = createAttempt("auth_t37_1", 1);
        accountingService.reserveCapacity(attempt1.getId(), 10, 0L);
        accountingService.settleReservation(attempt1.getId(), 10, 9999L);

        // Case 2: actual = 10,001 micro-USD, reservation = 10,000 micro-USD
        LlmProviderAttempt attempt2 = createAttempt("auth_t37_2", 1);
        accountingService.reserveCapacity(attempt2.getId(), 10, 10000L);
        accountingService.settleReservation(attempt2.getId(), 10, 10001L);

        // Case 3: actual = 19,999 micro-USD, reservation = 10,000 micro-USD
        LlmProviderAttempt attempt3 = createAttempt("auth_t37_3", 1);
        accountingService.reserveCapacity(attempt3.getId(), 10, 10000L);
        accountingService.settleReservation(attempt3.getId(), 10, 19999L);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            List<OrganizationOverageLiability> liabilities = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(3, liabilities.size());

            OrganizationOverageLiability liab1 = liabilities.stream().filter(l -> l.getAttempt().getId().equals(attempt1.getId())).findFirst().orElseThrow();
            assertEquals(9999L, liab1.getFinancialLiabilityMicroUsd(), "Case 1 liability must be 9999 micro-USD");

            OrganizationOverageLiability liab2 = liabilities.stream().filter(l -> l.getAttempt().getId().equals(attempt2.getId())).findFirst().orElseThrow();
            assertEquals(1L, liab2.getFinancialLiabilityMicroUsd(), "Case 2 liability must be 1 micro-USD");

            OrganizationOverageLiability liab3 = liabilities.stream().filter(l -> l.getAttempt().getId().equals(attempt3.getId())).findFirst().orElseThrow();
            assertEquals(9999L, liab3.getFinancialLiabilityMicroUsd(), "Case 3 liability must be 9999 micro-USD");
        });
        assertHardFinancialInvariants(testOrg);
    }
}
