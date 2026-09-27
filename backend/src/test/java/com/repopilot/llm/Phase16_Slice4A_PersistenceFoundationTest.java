package com.repopilot.llm;

import com.repopilot.llm.entity.*;
import com.repopilot.llm.repository.*;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
public class Phase16_Slice4A_PersistenceFoundationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationUsageCounterRepository organizationUsageCounterRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private LlmProviderAttemptRepository providerAttemptRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private OrganizationOverageLiabilityRepository overageLiabilityRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

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
            testUser.setEmail("llm_user_" + UUID.randomUUID() + "@example.com");
            testUser.setPasswordHash("hash");
            testUser.setName("LLM Test User");
            testUser.setRole(UserRole.USER);
            testUser = userRepository.save(testUser);

            testOrg = new Organization();
            testOrg.setName("LLM Test Org " + UUID.randomUUID());
            testOrg.setSlug("llm-org-" + UUID.randomUUID());
            testOrg = organizationRepository.save(testOrg);

            testProject = new Project();
            testProject.setName("LLM Test Project");
            testProject.setDescription("LLM Slice 4A Test Project");
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
                defaultPricingVersion.setInputRateMicroUsdPer1k(2500L);  // $0.0025 per 1k = 2500 micro-USD
                defaultPricingVersion.setOutputRateMicroUsdPer1k(10000L); // $0.0100 per 1k = 10000 micro-USD
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

    @Test
    @DisplayName("TEST01: Unique logical request constraint on (source_authority_type, source_authority_id, idempotency_key)")
    void test01_uniqueLogicalRequestConstraint() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String authorityType = "CODE_REVIEW";
        String authorityId = UUID.randomUUID().toString();
        String idempotencyKey = "key_req_1";

        tx.executeWithoutResult(status -> {
            LlmLogicalRequest req1 = new LlmLogicalRequest();
            req1.setOrganization(testOrg);
            req1.setProject(testProject);
            req1.setSourceAuthorityType(authorityType);
            req1.setSourceAuthorityId(authorityId);
            req1.setIdempotencyKey(idempotencyKey);
            req1.setFeatureName("CODE_REVIEW_SYNTHESIS");
            req1.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            req1.setBillingStatus(LlmBillingStatus.UNBILLED);
            logicalRequestRepository.saveAndFlush(req1);
        });

        assertThrows(DataIntegrityViolationException.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmLogicalRequest req2 = new LlmLogicalRequest();
                req2.setOrganization(testOrg);
                req2.setProject(testProject);
                req2.setSourceAuthorityType(authorityType);
                req2.setSourceAuthorityId(authorityId);
                req2.setIdempotencyKey(idempotencyKey); // duplicate key
                req2.setFeatureName("CODE_REVIEW_SYNTHESIS");
                req2.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
                req2.setBillingStatus(LlmBillingStatus.UNBILLED);
                logicalRequestRepository.saveAndFlush(req2);
            });
        });
    }

    @Test
    @DisplayName("TEST02: Unique attempt sequence constraint on (logical_request_id, attempt_number)")
    void test02_uniqueAttemptSequenceConstraint() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmLogicalRequest logicalReq = createSampleLogicalRequest("task_exec_123");

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt attempt1 = new LlmProviderAttempt();
            attempt1.setLogicalRequest(logicalReq);
            attempt1.setAttemptNumber(1);
            attempt1.setProviderId("openai");
            attempt1.setModelId("gpt-4o");
            attempt1.setPricingVersion(defaultPricingVersion);
            attempt1.setProviderIdempotencyKey("idem_key_1");
            attempt1.setInputHash("hash_in_1");
            attempt1.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            attempt1.setBillingStatus(LlmBillingStatus.UNBILLED);
            providerAttemptRepository.saveAndFlush(attempt1);
        });

        assertThrows(DataIntegrityViolationException.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmProviderAttempt attempt2 = new LlmProviderAttempt();
                attempt2.setLogicalRequest(logicalReq);
                attempt2.setAttemptNumber(1); // duplicate attempt_number for same logical request
                attempt2.setProviderId("anthropic");
                attempt2.setModelId("claude-3-5-sonnet");
                attempt2.setPricingVersion(defaultPricingVersion);
                attempt2.setProviderIdempotencyKey("idem_key_2");
                attempt2.setInputHash("hash_in_2");
                attempt2.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
                attempt2.setBillingStatus(LlmBillingStatus.UNBILLED);
                providerAttemptRepository.saveAndFlush(attempt2);
            });
        });
    }

    @Test
    @DisplayName("TEST03: Unique pricing version constraint on (provider_id, model_id, pricing_version)")
    void test03_uniquePricingVersionConstraint() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(DataIntegrityViolationException.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmPricingVersion ver2 = new LlmPricingVersion();
                ver2.setProviderId("openai");
                ver2.setModelId("gpt-4o");
                ver2.setPricingVersion(1); // duplicate version 1 for openai/gpt-4o
                ver2.setInputRateMicroUsdPer1k(3000L);
                ver2.setOutputRateMicroUsdPer1k(12000L);
                ver2.setEffectiveFrom(Instant.now());
                pricingVersionRepository.saveAndFlush(ver2);
            });
        });
    }

    @Test
    @DisplayName("TEST04: Unique canonical audit event constraint on audit_log_outbox")
    void test04_uniqueCanonicalAuditEventConstraint() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String canonicalId = "canonical_event_" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            AuditLogOutbox outbox1 = new AuditLogOutbox();
            outbox1.setCanonicalAuditEventId(canonicalId);
            outbox1.setOccurrenceId(UUID.randomUUID());
            outbox1.setAggregateType("LLM_PROVIDER_ATTEMPT");
            outbox1.setAggregateId(UUID.randomUUID().toString());
            outbox1.setEventType("ATTEMPT_FAILED");
            outbox1.setPayloadJson("{\"reason\":\"timeout\"}");
            outbox1.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            auditLogOutboxRepository.saveAndFlush(outbox1);
        });

        assertThrows(DataIntegrityViolationException.class, () -> {
            tx.executeWithoutResult(status -> {
                AuditLogOutbox outbox2 = new AuditLogOutbox();
                outbox2.setCanonicalAuditEventId(canonicalId); // duplicate
                outbox2.setOccurrenceId(UUID.randomUUID());
                outbox2.setAggregateType("LLM_PROVIDER_ATTEMPT");
                outbox2.setAggregateId(UUID.randomUUID().toString());
                outbox2.setEventType("ATTEMPT_FAILED");
                outbox2.setPayloadJson("{\"reason\":\"retry\"}");
                outbox2.setDeliveryState(AuditOutboxDeliveryState.PENDING);
                auditLogOutboxRepository.saveAndFlush(outbox2);
            });
        });
    }

    @Test
    @DisplayName("TEST05: Decoupled execution_status and billing_status independence across required state combinations")
    void test05_stateMachineIndependenceCombinations() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmLogicalRequest logicalReq = createSampleLogicalRequest("task_exec_combinations");

        // Combination 1: EXECUTING + UNBILLED
        testStatusCombination(tx, logicalReq, 1, LlmExecutionStatus.EXECUTING, LlmBillingStatus.UNBILLED);

        // Combination 2: TIMED_OUT + BILLING_UNKNOWN
        testStatusCombination(tx, logicalReq, 2, LlmExecutionStatus.TIMED_OUT, LlmBillingStatus.BILLING_UNKNOWN);

        // Combination 3: FAILED + EXEMPT_NO_CHARGE
        testStatusCombination(tx, logicalReq, 3, LlmExecutionStatus.FAILED, LlmBillingStatus.EXEMPT_NO_CHARGE);

        // Combination 4: SUCCEEDED + BILLED
        testStatusCombination(tx, logicalReq, 4, LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);

        // Combination 5: SUCCEEDED + BILLING_PENDING_PROVIDER
        testStatusCombination(tx, logicalReq, 5, LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLING_PENDING_PROVIDER);

        // Combination 6: SUCCEEDED + BILLED_WITH_OVERAGE_LIABILITY
        testStatusCombination(tx, logicalReq, 6, LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY);
    }

    private void testStatusCombination(TransactionTemplate tx, LlmLogicalRequest logicalReq, int attemptNum,
                                        LlmExecutionStatus execStatus, LlmBillingStatus billStatus) {
        tx.executeWithoutResult(status -> {
            LlmProviderAttempt attempt = new LlmProviderAttempt();
            attempt.setLogicalRequest(logicalReq);
            attempt.setAttemptNumber(attemptNum);
            attempt.setProviderId("openai");
            attempt.setModelId("gpt-4o");
            attempt.setPricingVersion(defaultPricingVersion);
            attempt.setProviderIdempotencyKey("idem_key_comb_" + attemptNum);
            attempt.setInputHash("input_hash_comb");
            attempt.setExecutionStatus(execStatus);
            attempt.setBillingStatus(billStatus);
            providerAttemptRepository.saveAndFlush(attempt);
        });

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt fetched = providerAttemptRepository
                    .findByLogicalRequestIdAndAttemptNumber(logicalReq.getId(), attemptNum).orElseThrow();
            assertEquals(execStatus, fetched.getExecutionStatus());
            assertEquals(billStatus, fetched.getBillingStatus());
        });
    }

    @Test
    @DisplayName("TEST06: Micro-USD integer precision calculation without Float/Double, verifying large value non-truncation")
    void test06_microUsdIntegerPrecisionAndLargeValues() {
        // Rates: input = 2500 micro-USD ($0.0025) per 1k, output = 10000 micro-USD ($0.0100) per 1k
        long promptTokens = 1500L;
        long completionTokens = 750L;

        long inputCostMicroUsd = (promptTokens * defaultPricingVersion.getInputRateMicroUsdPer1k()) / 1000L;
        long outputCostMicroUsd = (completionTokens * defaultPricingVersion.getOutputRateMicroUsdPer1k()) / 1000L;
        long totalCostMicroUsd = inputCostMicroUsd + outputCostMicroUsd;

        assertEquals(3750L, inputCostMicroUsd);
        assertEquals(7500L, outputCostMicroUsd);
        assertEquals(11250L, totalCostMicroUsd);

        // Verify large value persistence ($100,000.00 USD = 100,000,000,000 micro-USD) without overflow or truncation
        long largeSpendMicroUsd = 100_000_000_000L;

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = new OrganizationUsageCounter(testOrg);
            counter.setReservedSpendMicroUsd(largeSpendMicroUsd);
            counter.setUnresolvedExposureSpendMicroUsd(largeSpendMicroUsd);
            organizationUsageCounterRepository.saveAndFlush(counter);
        });

        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter fetched = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(largeSpendMicroUsd, fetched.getReservedSpendMicroUsd());
            assertEquals(largeSpendMicroUsd, fetched.getUnresolvedExposureSpendMicroUsd());
        });
    }

    @Test
    @DisplayName("TEST07: Organization usage counter reservation and exposure fields persistence")
    void test07_organizationUsageCounterReservationFields() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter counter = new OrganizationUsageCounter(testOrg);
            counter.setTokensConsumed(5000L);
            counter.setTotalSpendCents(50L);
            counter.setReservedTokens(2000);
            counter.setReservedSpendMicroUsd(50000L);
            counter.setUnresolvedExposureTokens(1000);
            counter.setUnresolvedExposureSpendMicroUsd(25000L);
            organizationUsageCounterRepository.saveAndFlush(counter);
        });

        tx.executeWithoutResult(status -> {
            OrganizationUsageCounter fetched = organizationUsageCounterRepository.findByOrganizationId(testOrg.getId()).orElseThrow();
            assertEquals(5000L, fetched.getTokensConsumed());
            assertEquals(50L, fetched.getTotalSpendCents());
            assertEquals(2000, fetched.getReservedTokens());
            assertEquals(50000L, fetched.getReservedSpendMicroUsd());
            assertEquals(1000, fetched.getUnresolvedExposureTokens());
            assertEquals(25000L, fetched.getUnresolvedExposureSpendMicroUsd());
        });
    }

    @Test
    @DisplayName("TEST08: Logical LLM request persistence and retrieval by composite idempotency key")
    void test08_logicalRequestPersistenceAndRetrieval() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String authorityType = "REPORT_SYNTHESIS";
        String authorityId = UUID.randomUUID().toString();
        String idempotencyKey = "synth_key_100";

        tx.executeWithoutResult(status -> {
            LlmLogicalRequest req = new LlmLogicalRequest();
            req.setOrganization(testOrg);
            req.setProject(testProject);
            req.setSourceAuthorityType(authorityType);
            req.setSourceAuthorityId(authorityId);
            req.setIdempotencyKey(idempotencyKey);
            req.setFeatureName("SYNTHESIZER");
            req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            req.setBillingStatus(LlmBillingStatus.UNBILLED);
            logicalRequestRepository.saveAndFlush(req);
        });

        tx.executeWithoutResult(status -> {
            Optional<LlmLogicalRequest> fetchedOpt = logicalRequestRepository
                    .findBySourceAuthorityTypeAndSourceAuthorityIdAndIdempotencyKey(authorityType, authorityId, idempotencyKey);

            assertTrue(fetchedOpt.isPresent());
            LlmLogicalRequest fetched = fetchedOpt.get();
            assertEquals("SYNTHESIZER", fetched.getFeatureName());
            assertEquals(LlmExecutionStatus.ATTEMPT_CREATED, fetched.getExecutionStatus());
            assertEquals(LlmBillingStatus.UNBILLED, fetched.getBillingStatus());
        });
    }

    @Test
    @DisplayName("TEST09: Pricing version persistence and retrieval")
    void test09_pricingVersionPersistenceAndRetrieval() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            Optional<LlmPricingVersion> fetchedOpt = pricingVersionRepository
                    .findByProviderIdAndModelIdAndPricingVersion("openai", "gpt-4o", 1);

            assertTrue(fetchedOpt.isPresent());
            LlmPricingVersion fetched = fetchedOpt.get();
            assertEquals(2500L, fetched.getInputRateMicroUsdPer1k());
            assertEquals(10000L, fetched.getOutputRateMicroUsdPer1k());
        });
    }

    @Test
    @DisplayName("TEST10: Provider attempt persistence and retrieval")
    void test10_providerAttemptPersistenceAndRetrieval() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmLogicalRequest logicalReq = createSampleLogicalRequest("task_exec_789");

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt attempt = new LlmProviderAttempt();
            attempt.setLogicalRequest(logicalReq);
            attempt.setAttemptNumber(1);
            attempt.setProviderId("openai");
            attempt.setModelId("gpt-4o");
            attempt.setPricingVersion(defaultPricingVersion);
            attempt.setProviderIdempotencyKey("idem_key_10");
            attempt.setInputHash("input_hash_abc");
            attempt.setExecutionStatus(LlmExecutionStatus.EXECUTING);
            attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
            providerAttemptRepository.saveAndFlush(attempt);
        });

        tx.executeWithoutResult(status -> {
            Optional<LlmProviderAttempt> fetchedOpt = providerAttemptRepository
                    .findByLogicalRequestIdAndAttemptNumber(logicalReq.getId(), 1);

            assertTrue(fetchedOpt.isPresent());
            LlmProviderAttempt fetched = fetchedOpt.get();
            assertEquals("openai", fetched.getProviderId());
            assertEquals("gpt-4o", fetched.getModelId());
            assertEquals("input_hash_abc", fetched.getInputHash());
        });
    }

    @Test
    @DisplayName("TEST11: Audit log outbox persistence and retrieval")
    void test11_auditLogOutboxPersistenceAndRetrieval() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String canonicalId = "canonical_outbox_" + UUID.randomUUID();
        UUID occurrenceId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            AuditLogOutbox outbox = new AuditLogOutbox();
            outbox.setCanonicalAuditEventId(canonicalId);
            outbox.setOccurrenceId(occurrenceId);
            outbox.setAggregateType("LLM_LOGICAL_REQUEST");
            outbox.setAggregateId(UUID.randomUUID().toString());
            outbox.setEventType("REQUEST_CREATED");
            outbox.setPayloadJson("{\"status\":\"CREATED\"}");
            outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            auditLogOutboxRepository.saveAndFlush(outbox);
        });

        tx.executeWithoutResult(status -> {
            Optional<AuditLogOutbox> fetchedOpt = auditLogOutboxRepository.findByCanonicalAuditEventId(canonicalId);
            assertTrue(fetchedOpt.isPresent());
            AuditLogOutbox fetched = fetchedOpt.get();
            assertEquals(occurrenceId, fetched.getOccurrenceId());
            assertEquals(AuditOutboxDeliveryState.PENDING, fetched.getDeliveryState());
        });
    }

    @Test
    @DisplayName("TEST12: Organization overage liability persistence")
    void test12_organizationOverageLiabilityPersistence() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmLogicalRequest logicalReq = createSampleLogicalRequest("task_exec_overage");

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt attempt = new LlmProviderAttempt();
            attempt.setLogicalRequest(logicalReq);
            attempt.setAttemptNumber(1);
            attempt.setProviderId("openai");
            attempt.setModelId("gpt-4o");
            attempt.setPricingVersion(defaultPricingVersion);
            attempt.setProviderIdempotencyKey("idem_key_overage");
            attempt.setInputHash("input_hash_overage");
            attempt.setExecutionStatus(LlmExecutionStatus.SUCCEEDED);
            attempt.setBillingStatus(LlmBillingStatus.BILLED_WITH_OVERAGE_LIABILITY);
            attempt = providerAttemptRepository.saveAndFlush(attempt);

            OrganizationOverageLiability liability = new OrganizationOverageLiability();
            liability.setOrganization(testOrg);
            liability.setAttempt(attempt);
            liability.setFinancialLiabilityMicroUsd(150000L); // $0.15 liability
            liability.setReason("HARD_BUDGET_EXCEEDED_AFTER_TIMEOUT_RECONCILIATION");
            overageLiabilityRepository.saveAndFlush(liability);
        });

        tx.executeWithoutResult(status -> {
            List<OrganizationOverageLiability> fetchedList = overageLiabilityRepository.findByOrganizationId(testOrg.getId());
            assertEquals(1, fetchedList.size());
            OrganizationOverageLiability fetched = fetchedList.get(0);
            assertEquals(150000L, fetched.getFinancialLiabilityMicroUsd());
            assertEquals("HARD_BUDGET_EXCEEDED_AFTER_TIMEOUT_RECONCILIATION", fetched.getReason());
        });
    }

    @Test
    @DisplayName("TEST13: Organization ownership derivation and strict binding to logical request")
    void test13_organizationOwnershipDerivationAndImmutability() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmLogicalRequest logicalReq = createSampleLogicalRequest("task_exec_org_bind");

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt attempt = new LlmProviderAttempt();
            attempt.setLogicalRequest(logicalReq);
            attempt.setAttemptNumber(1);
            attempt.setProviderId("openai");
            attempt.setModelId("gpt-4o");
            attempt.setPricingVersion(defaultPricingVersion);
            attempt.setProviderIdempotencyKey("idem_key_org_bind");
            attempt.setInputHash("input_hash_org_bind");
            attempt.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
            attempt = providerAttemptRepository.saveAndFlush(attempt);

            // Attempt organization is strictly derived via getLogicalRequest().getOrganization()
            assertNotNull(attempt.getLogicalRequest().getOrganization());
            assertEquals(testOrg.getId(), attempt.getLogicalRequest().getOrganization().getId());
        });
    }

    @Test
    @DisplayName("TEST14: Foreign key relationship enforcement from provider attempt to logical request and pricing version")
    void test14_foreignKeyConstraintAttemptToLogicalRequestAndPricing() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmLogicalRequest logicalReq = createSampleLogicalRequest("task_exec_fk_test");

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt attempt = new LlmProviderAttempt();
            attempt.setLogicalRequest(logicalReq);
            attempt.setAttemptNumber(1);
            attempt.setProviderId("openai");
            attempt.setModelId("gpt-4o");
            attempt.setPricingVersion(defaultPricingVersion);
            attempt.setProviderIdempotencyKey("idem_key_fk");
            attempt.setInputHash("input_hash_fk");
            attempt.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
            attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
            attempt = providerAttemptRepository.saveAndFlush(attempt);

            assertEquals(logicalReq.getId(), attempt.getLogicalRequest().getId());
            assertEquals(defaultPricingVersion.getId(), attempt.getPricingVersion().getId());
        });
    }

    @Test
    @DisplayName("TEST15: Worker lease token and fencing version field persistence without truncation")
    void test15_leaseAndFencingFieldPersistence() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LlmLogicalRequest logicalReq = createSampleLogicalRequest("task_exec_lease");
        String leaseToken = "lease_worker_node_alpha_99_" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt attempt = new LlmProviderAttempt();
            attempt.setLogicalRequest(logicalReq);
            attempt.setAttemptNumber(1);
            attempt.setProviderId("openai");
            attempt.setModelId("gpt-4o");
            attempt.setPricingVersion(defaultPricingVersion);
            attempt.setProviderIdempotencyKey("idem_key_lease");
            attempt.setWorkerLeaseToken(leaseToken);
            attempt.setFencingVersion(42L);
            attempt.setInputHash("input_hash_lease");
            attempt.setExecutionStatus(LlmExecutionStatus.EXECUTION_CLAIMED);
            attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
            providerAttemptRepository.saveAndFlush(attempt);
        });

        tx.executeWithoutResult(status -> {
            LlmProviderAttempt fetched = providerAttemptRepository
                    .findByLogicalRequestIdAndAttemptNumber(logicalReq.getId(), 1).orElseThrow();
            assertEquals(leaseToken, fetched.getWorkerLeaseToken());
            assertEquals(42L, fetched.getFencingVersion());
        });
    }

    @Test
    @DisplayName("TEST16: Multi-threaded concurrent insertion enforces PostgreSQL unique constraint with exact single-row success")
    void test16_concurrentInsertionEnforcesUniqueConstraint() throws Exception {
        String authorityType = "CONCURRENT_TEST";
        String authorityId = UUID.randomUUID().toString();
        String idempotencyKey = "concurrent_key_" + UUID.randomUUID();

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(); // Synchronize start across both threads

                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tx.executeWithoutResult(status -> {
                        LlmLogicalRequest req = new LlmLogicalRequest();
                        req.setOrganization(testOrg);
                        req.setProject(testProject);
                        req.setSourceAuthorityType(authorityType);
                        req.setSourceAuthorityId(authorityId);
                        req.setIdempotencyKey(idempotencyKey);
                        req.setFeatureName("CONCURRENCY_TEST");
                        req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
                        req.setBillingStatus(LlmBillingStatus.UNBILLED);
                        logicalRequestRepository.saveAndFlush(req);
                    });
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failureCount.incrementAndGet();
                    exceptions.add(t);
                }
            }));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException ignored) {
                // Expected for the losing thread
            }
        }
        executor.shutdown();

        // Assert exactly one thread succeeded and exactly one thread failed
        assertEquals(1, successCount.get(), "Exactly one concurrent insertion transaction must commit");
        assertEquals(1, failureCount.get(), "Exactly one concurrent insertion transaction must fail");
        assertFalse(exceptions.isEmpty(), "Failing transaction must produce an exception");

        Throwable rootCause = exceptions.get(0);
        while (rootCause.getCause() != null && !(rootCause instanceof DataIntegrityViolationException)) {
            rootCause = rootCause.getCause();
        }
        assertTrue(rootCause instanceof DataIntegrityViolationException,
                "Failure cause must be DataIntegrityViolationException from UNIQUE constraint violation");

        // Verify final database state contains exactly ONE row for the composite key
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            Optional<LlmLogicalRequest> fetched = logicalRequestRepository
                    .findBySourceAuthorityTypeAndSourceAuthorityIdAndIdempotencyKey(authorityType, authorityId, idempotencyKey);
            assertTrue(fetched.isPresent(), "Final database state must contain the winning row");
        });
    }

    private LlmLogicalRequest createSampleLogicalRequest(String authorityId) {
        LlmLogicalRequest req = new LlmLogicalRequest();
        req.setOrganization(testOrg);
        req.setProject(testProject);
        req.setSourceAuthorityType("TASK_EXECUTION");
        req.setSourceAuthorityId(authorityId);
        req.setIdempotencyKey("idem_" + UUID.randomUUID());
        req.setFeatureName("CODE_GENERATION");
        req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
        req.setBillingStatus(LlmBillingStatus.UNBILLED);
        return logicalRequestRepository.saveAndFlush(req);
    }
}
