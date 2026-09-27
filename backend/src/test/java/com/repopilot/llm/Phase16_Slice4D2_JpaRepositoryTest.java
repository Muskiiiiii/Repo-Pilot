package com.repopilot.llm;

import com.repopilot.llm.entity.*;
import com.repopilot.llm.repository.*;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice4D2_JpaRepositoryTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private LlmProviderAttemptRepository providerAttemptRepository;
    @Autowired private LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository;
    @Autowired private LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository;
    @Autowired private LlmFallbackDecisionRepository fallbackDecisionRepository;

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
                jdbcTemplate.update("DELETE FROM llm_fallback_decisions");
                jdbcTemplate.update("DELETE FROM llm_provider_attempts");
                jdbcTemplate.update("DELETE FROM llm_active_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_exceptional_fallback_policies");
                jdbcTemplate.update("DELETE FROM llm_logical_requests");
                jdbcTemplate.update("DELETE FROM projects");
                jdbcTemplate.update("DELETE FROM organizations WHERE slug LIKE 'd2-test-%'");
                jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'd2-test-%'");
            } catch (Exception ignored) {}
        });
    }

    private User createTestUser() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setEmail("d2-test-" + id + "@example.com");
        user.setPasswordHash("hash");
        user.setName("D2 User");
        user.setRole(com.repopilot.user.entity.UserRole.USER);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private Organization createTestOrg() {
        UUID id = UUID.randomUUID();
        Organization org = new Organization();
        org.setName("D2 Org " + id);
        org.setSlug("d2-test-" + id);
        return organizationRepository.save(org);
    }

    private Project createTestProject(Organization org, User user) {
        Project proj = new Project();
        proj.setName("D2 Project");
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

    private static final java.util.concurrent.atomic.AtomicInteger PRICING_VERSION_COUNTER = new java.util.concurrent.atomic.AtomicInteger(40000);

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

    private LlmProviderAttempt createProviderAttempt(LlmLogicalRequest lr, int attemptNum, LlmPricingVersion pv) {
        LlmProviderAttempt attempt = new LlmProviderAttempt();
        attempt.setLogicalRequest(lr);
        attempt.setAttemptNumber(attemptNum);
        attempt.setProviderId("openai");
        attempt.setModelId("gpt-4o");
        attempt.setPricingVersion(pv);
        attempt.setProviderIdempotencyKey(UUID.randomUUID().toString());
        attempt.setInputHash("hash");
        attempt.setExecutionStatus(LlmExecutionStatus.FAILED);
        attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
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

    private LlmFallbackDecision createFallbackDecision(Organization org, LlmLogicalRequest lr, LlmProviderAttempt sourceAttempt, LlmExceptionalFallbackPolicy policy) {
        LlmFallbackDecision decision = new LlmFallbackDecision();
        decision.setOrganization(org);
        decision.setLogicalRequest(lr);
        decision.setSourceAttempt(sourceAttempt);
        decision.setDecisionSequence(1);
        decision.setOriginalProviderId("openai");
        decision.setOriginalModelId("gpt-4o");
        decision.setTargetProviderId("anthropic");
        decision.setTargetModelId("claude-3-5-sonnet");
        decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
        decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
        decision.setExceptionalPolicy(policy);
        return fallbackDecisionRepository.save(decision);
    }

    // =========================================================================
    // TEST-D2-01: Policy entity persists successfully
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-01: LlmExceptionalFallbackPolicy entity persists successfully")
    void testD2_01_policyEntityPersists() {
        Organization org = createTestOrg();
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);

        assertNotNull(policy.getId(), "Persisted policy must have non-null UUID ID");
        Optional<LlmExceptionalFallbackPolicy> found = exceptionalPolicyRepository.findById(policy.getId());
        assertTrue(found.isPresent(), "Saved policy must be retrievable by ID");
        assertEquals("openai", found.get().getOriginalProviderId());
        assertEquals("anthropic", found.get().getTargetProviderId());
    }

    // =========================================================================
    // TEST-D2-02: Policy version uniqueness enforced
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-02: Policy version uniqueness is enforced by uk_llm_exceptional_policy_ver")
    void testD2_02_policyVersionUniquenessEnforced() {
        Organization org = createTestOrg();
        createExceptionalPolicy(org, 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmExceptionalFallbackPolicy duplicate = new LlmExceptionalFallbackPolicy(
                    org, "TASK_EXECUTION", "openai", "anthropic", 1, null, "Duplicate Version"
                );
                exceptionalPolicyRepository.saveAndFlush(duplicate);
            });
        });
    }

    // =========================================================================
    // TEST-D2-03: Policy immutable UPDATE is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-03: Policy immutable UPDATE is rejected by DB trigger")
    void testD2_03_policyImmutableUpdateRejected() {
        Organization org = createTestOrg();
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE llm_exceptional_fallback_policies SET reason = 'Illegal edit' WHERE id = ?", policy.getId());
            });
        });
    }

    // =========================================================================
    // TEST-D2-04: Active pointer persists against matching policy identity
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-04: LlmActiveExceptionalFallbackPolicy persists against matching policy identity")
    void testD2_04_activePointerPersists() {
        Organization org = createTestOrg();
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        LlmActiveExceptionalFallbackPolicy active = createActivePointer(org, policy);

        assertNotNull(active.getId(), "Persisted active pointer must have non-null ID");
        Optional<LlmActiveExceptionalFallbackPolicy> found = activePolicyRepository.findById(active.getId());
        assertTrue(found.isPresent(), "Saved active pointer must be retrievable");
        assertEquals(policy.getId(), found.get().getActivePolicy().getId());
    }

    // =========================================================================
    // TEST-D2-05: Cross-tenant active pointer cannot persist
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-05: Cross-tenant active pointer is rejected by fk_active_policy_identity")
    void testD2_05_crossTenantActivePointerRejected() {
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        LlmExceptionalFallbackPolicy policyB = createExceptionalPolicy(orgB, 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmActiveExceptionalFallbackPolicy crossPointer = new LlmActiveExceptionalFallbackPolicy(
                    orgA, "TASK_EXECUTION", "openai", "anthropic", policyB
                );
                activePolicyRepository.saveAndFlush(crossPointer);
            });
        });
    }

    // =========================================================================
    // TEST-D2-06: Active pointer referencing mismatched provider identity cannot persist
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-06: Active pointer referencing mismatched provider tuple is rejected by fk_active_policy_identity")
    void testD2_06_mismatchedProviderActivePointerRejected() {
        Organization org = createTestOrg();
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1); // openai -> anthropic

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmActiveExceptionalFallbackPolicy mismatched = new LlmActiveExceptionalFallbackPolicy(
                    org, "TASK_EXECUTION", "google", "anthropic", policy
                );
                activePolicyRepository.saveAndFlush(mismatched);
            });
        });
    }

    // =========================================================================
    // TEST-D2-07: Fallback decision persists against same-tenant logical request
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-07: LlmFallbackDecision persists successfully against same-tenant logical request")
    void testD2_07_fallbackDecisionPersists() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv);
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);

        LlmFallbackDecision decision = createFallbackDecision(org, lr, attempt, policy);
        assertNotNull(decision.getId(), "Persisted fallback decision must have non-null ID");
        Optional<LlmFallbackDecision> found = fallbackDecisionRepository.findById(decision.getId());
        assertTrue(found.isPresent());
        assertEquals(LlmFallbackDecisionStatus.DECISION_INITIATED, found.get().getDecisionStatus());
    }

    // =========================================================================
    // TEST-D2-08: Cross-tenant logical request reference is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-08: Fallback decision for Org A referencing Logical Request of Org B is rejected by composite FK")
    void testD2_08_crossTenantLogicalRequestRejected() {
        User userB = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projB = createTestProject(orgB, userB);
        LlmLogicalRequest lrB = createLogicalRequest(orgB, projB);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attemptB = createProviderAttempt(lrB, 1, pv);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmFallbackDecision decision = new LlmFallbackDecision();
                decision.setOrganization(orgA);
                decision.setLogicalRequest(lrB);
                decision.setSourceAttempt(attemptB);
                decision.setDecisionSequence(1);
                decision.setOriginalProviderId("openai");
                decision.setOriginalModelId("gpt-4o");
                decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
                decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
                fallbackDecisionRepository.saveAndFlush(decision);
            });
        });
    }

    // =========================================================================
    // TEST-D2-09: Cross-tenant source attempt reference is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-09: Fallback decision for Logical Request A referencing Attempt of Logical Request B is rejected")
    void testD2_09_crossTenantSourceAttemptRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr1 = createLogicalRequest(org, proj);
        LlmLogicalRequest lr2 = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt2 = createProviderAttempt(lr2, 1, pv);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmFallbackDecision decision = new LlmFallbackDecision();
                decision.setOrganization(org);
                decision.setLogicalRequest(lr1);
                decision.setSourceAttempt(attempt2); // Mismatched logical request
                decision.setDecisionSequence(1);
                decision.setOriginalProviderId("openai");
                decision.setOriginalModelId("gpt-4o");
                decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
                decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
                fallbackDecisionRepository.saveAndFlush(decision);
            });
        });
    }

    // =========================================================================
    // TEST-D2-10: Cross-tenant target attempt reference is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-10: Fallback decision for Logical Request A referencing Target Attempt of Logical Request B is rejected")
    void testD2_10_crossTenantTargetAttemptRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr1 = createLogicalRequest(org, proj);
        LlmLogicalRequest lr2 = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt1 = createProviderAttempt(lr1, 1, pv);
        LlmProviderAttempt targetAttempt2 = createProviderAttempt(lr2, 1, pv);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmFallbackDecision decision = new LlmFallbackDecision();
                decision.setOrganization(org);
                decision.setLogicalRequest(lr1);
                decision.setSourceAttempt(sourceAttempt1);
                decision.setTargetAttempt(targetAttempt2); // Mismatched logical request target
                decision.setDecisionSequence(1);
                decision.setOriginalProviderId("openai");
                decision.setOriginalModelId("gpt-4o");
                decision.setTargetProviderId("anthropic");
                decision.setTargetModelId("claude-3-5-sonnet");
                decision.setDecisionStatus(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED);
                decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
                fallbackDecisionRepository.saveAndFlush(decision);
            });
        });
    }

    // =========================================================================
    // TEST-D2-11: Cross-tenant exceptional policy reference is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-11: Fallback decision for Org A referencing Exceptional Policy of Org B is rejected by composite FK")
    void testD2_11_crossTenantExceptionalPolicyRejected() {
        User userA = createTestUser();
        Organization orgA = createTestOrg();
        Organization orgB = createTestOrg();
        Project projA = createTestProject(orgA, userA);
        LlmLogicalRequest lrA = createLogicalRequest(orgA, projA);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attemptA = createProviderAttempt(lrA, 1, pv);
        LlmExceptionalFallbackPolicy policyB = createExceptionalPolicy(orgB, 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmFallbackDecision decision = new LlmFallbackDecision();
                decision.setOrganization(orgA);
                decision.setLogicalRequest(lrA);
                decision.setSourceAttempt(attemptA);
                decision.setDecisionSequence(1);
                decision.setOriginalProviderId("openai");
                decision.setOriginalModelId("gpt-4o");
                decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
                decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
                decision.setExceptionalPolicy(policyB); // Mismatched org policy
                fallbackDecisionRepository.saveAndFlush(decision);
            });
        });
    }

    // =========================================================================
    // TEST-D2-12: Duplicate (source_attempt_id, decision_sequence) is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-12: Duplicate (source_attempt_id, decision_sequence) is rejected by uk_fallback_decision_trigger")
    void testD2_12_duplicateSourceAttemptAndSequenceRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv);
        createFallbackDecision(org, lr, attempt, null);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmFallbackDecision duplicate = new LlmFallbackDecision();
                duplicate.setOrganization(org);
                duplicate.setLogicalRequest(lr);
                duplicate.setSourceAttempt(attempt);
                duplicate.setDecisionSequence(1); // Duplicate sequence
                duplicate.setOriginalProviderId("openai");
                duplicate.setOriginalModelId("gpt-4o");
                duplicate.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
                duplicate.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
                fallbackDecisionRepository.saveAndFlush(duplicate);
            });
        });
    }

    // =========================================================================
    // TEST-D2-13: Duplicate target_attempt_id is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-13: Duplicate target_attempt_id is rejected by uk_fallback_decision_target")
    void testD2_13_duplicateTargetAttemptRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt1 = createProviderAttempt(lr, 1, pv);
        LlmProviderAttempt sourceAttempt2 = createProviderAttempt(lr, 2, pv);
        LlmProviderAttempt targetAttempt = createProviderAttempt(lr, 3, pv);

        LlmFallbackDecision d1 = new LlmFallbackDecision();
        d1.setOrganization(org);
        d1.setLogicalRequest(lr);
        d1.setSourceAttempt(sourceAttempt1);
        d1.setTargetAttempt(targetAttempt);
        d1.setDecisionSequence(1);
        d1.setOriginalProviderId("openai");
        d1.setOriginalModelId("gpt-4o");
        d1.setTargetProviderId("anthropic");
        d1.setTargetModelId("claude-3-5-sonnet");
        d1.setDecisionStatus(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED);
        d1.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
        fallbackDecisionRepository.save(d1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmFallbackDecision d2 = new LlmFallbackDecision();
                d2.setOrganization(org);
                d2.setLogicalRequest(lr);
                d2.setSourceAttempt(sourceAttempt2);
                d2.setTargetAttempt(targetAttempt); // Duplicate target attempt
                d2.setDecisionSequence(1);
                d2.setOriginalProviderId("openai");
                d2.setOriginalModelId("gpt-4o");
                d2.setTargetProviderId("anthropic");
                d2.setTargetModelId("claude-3-5-sonnet");
                d2.setDecisionStatus(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED);
                d2.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
                fallbackDecisionRepository.saveAndFlush(d2);
            });
        });
    }

    // =========================================================================
    // TEST-D2-14: Attempt-to-fallback-decision uniqueness is enforced
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-14: Attempt-to-fallback-decision uniqueness is enforced by uk_llm_attempt_fallback_decision")
    void testD2_14_attemptToFallbackDecisionUniquenessEnforced() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv);
        LlmFallbackDecision decision = createFallbackDecision(org, lr, sourceAttempt, null);

        LlmProviderAttempt targetAttempt1 = createProviderAttempt(lr, 2, pv);
        targetAttempt1.setFallbackDecision(decision);
        providerAttemptRepository.save(targetAttempt1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                LlmProviderAttempt targetAttempt2 = createProviderAttempt(lr, 3, pv);
                targetAttempt2.setFallbackDecision(decision); // Duplicate fallback_decision_id link
                providerAttemptRepository.saveAndFlush(targetAttempt2);
            });
        });
    }

    // =========================================================================
    // TEST-D2-15: Deleting policy referenced by active pointer is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-15: Deleting policy referenced by active pointer is rejected by ON DELETE RESTRICT")
    void testD2_15_deletingPolicyWithActivePointerRejected() {
        Organization org = createTestOrg();
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        createActivePointer(org, policy);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                exceptionalPolicyRepository.delete(policy);
                exceptionalPolicyRepository.flush();
            });
        });
    }

    // =========================================================================
    // TEST-D2-16: Deleting policy referenced by fallback decision is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-16: Deleting policy referenced by fallback decision is rejected by ON DELETE RESTRICT")
    void testD2_16_deletingPolicyWithFallbackDecisionRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv);
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        createFallbackDecision(org, lr, attempt, policy);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                exceptionalPolicyRepository.delete(policy);
                exceptionalPolicyRepository.flush();
            });
        });
    }

    // =========================================================================
    // TEST-D2-17: Deleting logical request referenced by fallback decision is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-17: Deleting logical request referenced by fallback decision is rejected by ON DELETE RESTRICT")
    void testD2_17_deletingLogicalRequestWithFallbackDecisionRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv);
        createFallbackDecision(org, lr, attempt, null);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                logicalRequestRepository.delete(lr);
                logicalRequestRepository.flush();
            });
        });
    }

    // =========================================================================
    // TEST-D2-18: Deleting source attempt referenced by fallback decision is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-18: Deleting source attempt referenced by fallback decision is rejected by ON DELETE RESTRICT")
    void testD2_18_deletingSourceAttemptWithFallbackDecisionRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv);
        createFallbackDecision(org, lr, attempt, null);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                providerAttemptRepository.delete(attempt);
                providerAttemptRepository.flush();
            });
        });
    }

    // =========================================================================
    // TEST-D2-19: Deleting target attempt referenced by fallback decision is rejected
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-19: Deleting target attempt referenced by fallback decision is rejected by ON DELETE RESTRICT")
    void testD2_19_deletingTargetAttemptWithFallbackDecisionRejected() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt sourceAttempt = createProviderAttempt(lr, 1, pv);
        LlmProviderAttempt targetAttempt = createProviderAttempt(lr, 2, pv);

        LlmFallbackDecision decision = new LlmFallbackDecision();
        decision.setOrganization(org);
        decision.setLogicalRequest(lr);
        decision.setSourceAttempt(sourceAttempt);
        decision.setTargetAttempt(targetAttempt);
        decision.setDecisionSequence(1);
        decision.setOriginalProviderId("openai");
        decision.setOriginalModelId("gpt-4o");
        decision.setTargetProviderId("anthropic");
        decision.setTargetModelId("claude-3-5-sonnet");
        decision.setDecisionStatus(LlmFallbackDecisionStatus.TARGET_ATTEMPT_CREATED);
        decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
        fallbackDecisionRepository.save(decision);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                providerAttemptRepository.delete(targetAttempt);
                providerAttemptRepository.flush();
            });
        });
    }

    // =========================================================================
    // TEST-D2-20: Existing V35 organization-delete semantics remain unchanged
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-20: Existing V35 organization deletion behavior is preserved under JPA mappings")
    void testD2_20_organizationDeleteSemanticsPreserved() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv);
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        createActivePointer(org, policy);
        createFallbackDecision(org, lr, attempt, policy);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Attempting to delete organization must be rejected by RESTRICT foreign keys
        assertThrows(Exception.class, () -> {
            tx.executeWithoutResult(status -> {
                organizationRepository.delete(org);
                organizationRepository.flush();
            });
        });

        // Verify zero partial deletions
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, org.getId()));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_exceptional_fallback_policies WHERE id = ?", Integer.class, policy.getId()));
    }

    // =========================================================================
    // TEST-D2-21: Existing Phase 16.4A/B accounting test integration sanity
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-21: Verify Phase 16.4D-2 JPA mappings do not interfere with LlmLogicalRequest or Organization entity operations")
    void testD2_21_accountingSanityCheck() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);

        assertNotNull(lr.getId());
        assertEquals(LlmBillingStatus.UNBILLED, lr.getBillingStatus());
    }

    // =========================================================================
    // TEST-D2-22: General repository lookup methods sanity
    // =========================================================================
    @Test
    @DisplayName("TEST-D2-22: Verify repository custom lookup query methods function correctly")
    void testD2_22_repositoryLookupSanity() {
        User user = createTestUser();
        Organization org = createTestOrg();
        Project proj = createTestProject(org, user);
        LlmLogicalRequest lr = createLogicalRequest(org, proj);
        LlmPricingVersion pv = createPricingVersion();
        LlmProviderAttempt attempt = createProviderAttempt(lr, 1, pv);
        LlmExceptionalFallbackPolicy policy = createExceptionalPolicy(org, 1);
        LlmActiveExceptionalFallbackPolicy active = createActivePointer(org, policy);
        LlmFallbackDecision decision = createFallbackDecision(org, lr, attempt, policy);

        Optional<LlmExceptionalFallbackPolicy> foundPol = exceptionalPolicyRepository
            .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderIdAndPolicyVersion(
                org.getId(), "TASK_EXECUTION", "openai", "anthropic", 1);
        assertTrue(foundPol.isPresent());

        Optional<LlmActiveExceptionalFallbackPolicy> foundAct = activePolicyRepository
            .findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(
                org.getId(), "TASK_EXECUTION", "openai", "anthropic");
        assertTrue(foundAct.isPresent());

        Optional<LlmFallbackDecision> foundDecSeq = fallbackDecisionRepository
            .findBySourceAttemptIdAndDecisionSequence(attempt.getId(), 1);
        assertTrue(foundDecSeq.isPresent());

        Optional<LlmProviderAttempt> foundAttemptByDec = providerAttemptRepository
            .findByFallbackDecisionId(decision.getId());
        assertFalse(foundAttemptByDec.isPresent()); // Not linked yet
    }
}
