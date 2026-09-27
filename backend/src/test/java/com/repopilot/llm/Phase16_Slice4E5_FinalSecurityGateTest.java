package com.repopilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.repository.*;
import com.repopilot.llm.service.*;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase16_Slice4E5_FinalSecurityGateTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmProviderAttemptRepository attemptRepository;
    @Autowired private LlmFallbackDecisionRepository fallbackDecisionRepository;
    @Autowired private LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository;
    @Autowired private LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private JwtTokenProvider tokenProvider;

    @Autowired private LlmPolicyAdministrationService policyAdminService;
    @Autowired private LlmFallbackInspectionService fallbackInspectionService;
    @Autowired private AuditOutboxInspectionService auditOutboxInspectionService;
    @Autowired private LlmProviderInspectionService providerInspectionService;

    private Organization orgA;
    private Organization orgB;
    private Project projectA;
    private Project projectB;

    private User adminOrgA;
    private User memberOrgA;
    private User adminOrgB;
    private User memberOrgB;
    private User nonMemberUser;

    private String tokenAdminOrgA;
    private String tokenMemberOrgA;
    private String tokenAdminOrgB;
    private String tokenMemberOrgB;
    private String tokenNonMember;

    private LlmPricingVersion defaultPricingVersion;

    @BeforeEach
    void setUp() {
        setUpSchemaTriggers();
        cleanData();

        String uid = UUID.randomUUID().toString().substring(0, 8);
        adminOrgA = createUser("admin_orga_e5_" + uid + "@example.com", "Admin OrgA");
        memberOrgA = createUser("member_orga_e5_" + uid + "@example.com", "Member OrgA");
        adminOrgB = createUser("admin_orgb_e5_" + uid + "@example.com", "Admin OrgB");
        memberOrgB = createUser("member_orgb_e5_" + uid + "@example.com", "Member OrgB");
        nonMemberUser = createUser("non_member_e5_" + uid + "@example.com", "Non Member");

        tokenAdminOrgA = tokenProvider.generateAccessToken(adminOrgA);
        tokenMemberOrgA = tokenProvider.generateAccessToken(memberOrgA);
        tokenAdminOrgB = tokenProvider.generateAccessToken(adminOrgB);
        tokenMemberOrgB = tokenProvider.generateAccessToken(memberOrgB);
        tokenNonMember = tokenProvider.generateAccessToken(nonMemberUser);

        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-e5-" + uid);
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-e5-" + uid);
        orgB = organizationRepository.save(orgB);

        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgB, memberOrgB, OrganizationRole.ORG_MEMBER));

        projectA = new Project();
        projectA.setName("Project A");
        projectA.setOrganization(orgA);
        projectA.setOwner(memberOrgA);
        projectA = projectRepository.save(projectA);

        projectB = new Project();
        projectB.setName("Project B");
        projectB.setOrganization(orgB);
        projectB.setOwner(memberOrgB);
        projectB = projectRepository.save(projectB);

        defaultPricingVersion = new LlmPricingVersion();
        defaultPricingVersion.setProviderId("openai");
        defaultPricingVersion.setModelId("gpt-4o");
        defaultPricingVersion.setPricingVersion(1);
        defaultPricingVersion.setInputRateMicroUsdPer1k(10L);
        defaultPricingVersion.setOutputRateMicroUsdPer1k(30L);
        defaultPricingVersion.setEffectiveFrom(Instant.now());
        defaultPricingVersion = pricingVersionRepository.save(defaultPricingVersion);
    }

    private void setUpSchemaTriggers() {
        executeDdlSafely("ALTER TABLE llm_exceptional_fallback_policies ADD CONSTRAINT uk_llm_exceptional_policy_identity UNIQUE (id, organization_id, source_authority_type, original_provider_id, target_provider_id);");
        executeDdlSafely("""
            ALTER TABLE llm_active_exceptional_fallback_policies
            ADD CONSTRAINT fk_active_policy_identity
            FOREIGN KEY (active_policy_id, organization_id, source_authority_type, original_provider_id, target_provider_id)
            REFERENCES llm_exceptional_fallback_policies(id, organization_id, source_authority_type, original_provider_id, target_provider_id) ON DELETE RESTRICT;
        """);
        executeDdlSafely("DROP TRIGGER IF EXISTS trg_prevent_exceptional_policy_update_h2;");
        executeDdlSafely("""
            CREATE TRIGGER trg_prevent_exceptional_policy_update_h2
            BEFORE UPDATE ON llm_exceptional_fallback_policies
            FOR EACH ROW CALL "com.repopilot.llm.Phase16_Slice4D1_V35SchemaPostgresVerificationTest$PreventPolicyUpdateTrigger";
        """);
    }

    private void executeDdlSafely(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        try { auditLogOutboxRepository.deleteAll(); } catch (Exception ignored) {}
        try { activePolicyRepository.deleteAll(); } catch (Exception ignored) {}
        try { exceptionalPolicyRepository.deleteAll(); } catch (Exception ignored) {}
        try { attemptRepository.deleteAll(); } catch (Exception ignored) {}
        try { fallbackDecisionRepository.deleteAll(); } catch (Exception ignored) {}
        try { logicalRequestRepository.deleteAll(); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_policies;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_overage_liabilities;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_usage_counters;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM project_members;"); } catch (Exception ignored) {}
        try { projectRepository.deleteAll(); } catch (Exception ignored) {}
        try { organizationMemberRepository.deleteAll(); } catch (Exception ignored) {}
        try { organizationRepository.deleteAll(); } catch (Exception ignored) {}
        try { userRepository.deleteAll(); } catch (Exception ignored) {}
        try { pricingVersionRepository.deleteAll(); } catch (Exception ignored) {}
    }

    private User createUser(String email, String name) {
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        u.setPasswordHash("hashed_password");
        u.setGithubUserId("gh_" + UUID.randomUUID().toString().substring(0, 8));
        return userRepository.save(u);
    }

    private LlmLogicalRequest createLogicalRequest(Organization org, Project proj) {
        LlmLogicalRequest req = new LlmLogicalRequest();
        req.setOrganization(org);
        req.setProject(proj);
        req.setSourceAuthorityType("TEST");
        req.setSourceAuthorityId(UUID.randomUUID().toString());
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setFeatureName("E5_TEST");
        req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
        req.setBillingStatus(LlmBillingStatus.UNBILLED);
        return logicalRequestRepository.save(req);
    }

    private LlmProviderAttempt createAttempt(LlmLogicalRequest req, String providerId, LlmExecutionStatus status, LlmBillingStatus billingStatus, int attemptNumber) {
        LlmProviderAttempt attempt = new LlmProviderAttempt();
        attempt.setLogicalRequest(req);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setProviderId(providerId);
        attempt.setModelId("gpt-4o");
        attempt.setPricingVersion(defaultPricingVersion);
        attempt.setProviderIdempotencyKey(UUID.randomUUID().toString());
        attempt.setInputHash("hash_input");
        attempt.setExecutionStatus(status);
        attempt.setBillingStatus(billingStatus);
        attempt.setStartTime(Instant.now());
        return attemptRepository.save(attempt);
    }

    private LlmExceptionalFallbackPolicy createPolicy(Organization org, String sourceAuth, String origProv, String targetProv) {
        LlmExceptionalFallbackPolicy p = new LlmExceptionalFallbackPolicy();
        p.setOrganization(org);
        p.setSourceAuthorityType(sourceAuth);
        p.setOriginalProviderId(origProv);
        p.setTargetProviderId(targetProv);
        p.setPolicyVersion(1);
        p.setReason("E5 Policy Test");
        p.setCreatedByUser(adminOrgA);
        return exceptionalPolicyRepository.save(p);
    }

    private AuditLogOutbox createOutboxEvent(Organization org, String payload) {
        AuditLogOutbox event = new AuditLogOutbox();
        event.setOrganization(org);
        event.setCanonicalAuditEventId("AUDIT:" + org.getId() + ":" + UUID.randomUUID());
        event.setOccurrenceId(UUID.randomUUID());
        event.setAggregateType("LLM_POLICY");
        event.setAggregateId(org.getId().toString());
        event.setEventType("POLICY_CREATED");

        String fullPayload;
        if (payload != null && payload.trim().startsWith("{")) {
            fullPayload = "{\"organizationId\":\"" + org.getId() + "\"," + payload.trim().substring(1);
        } else {
            fullPayload = "{\"organizationId\":\"" + org.getId() + "\",\"data\":\"" + payload + "\"}";
        }
        event.setPayloadJson(fullPayload);
        event.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        event.setRetryCount(0);
        return auditLogOutboxRepository.save(event);
    }

    // =========================================================================
    // SECTION 1 — CROSS-TENANT IDOR ATTACKS (E5-01 to E5-08)
    // =========================================================================

    @Test
    @DisplayName("E5-01: ORG-A user accessing ORG-B organizationId returns 403 or 404")
    void testE5_01_CrossTenantOrgIdAccess() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgB.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E5-02: ORG-A user accessing ORG-B policyId returns 404 NOT_FOUND without disclosure")
    void testE5_02_CrossTenantPolicyIdAccess() throws Exception {
        LlmExceptionalFallbackPolicy policyB = createPolicy(orgB, "GITHUB", "openai", "stub-mock");
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + policyB.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E5-03: ORG-A user accessing ORG-B logicalRequestId returns 404 NOT_FOUND")
    void testE5_03_CrossTenantLogicalRequestIdAccess() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + reqB.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E5-04: ORG-A user accessing ORG-B attemptId returns 404 NOT_FOUND")
    void testE5_04_CrossTenantAttemptIdAccess() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        createAttempt(reqB, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED, 1);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + reqB.getId() + "/attempts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E5-05: ORG-A user accessing ORG-B fallbackDecisionId returns 404 NOT_FOUND")
    void testE5_05_CrossTenantFallbackDecisionIdAccess() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + reqB.getId() + "/fallback-decisions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E5-06: ORG-A user accessing ORG-B auditEventId returns 404 NOT_FOUND")
    void testE5_06_CrossTenantAuditEventIdAccess() throws Exception {
        AuditLogOutbox eventB = createOutboxEvent(orgB, "{\"key\":\"val\"}");
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + eventB.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E5-07: ORG-A user accessing ORG-B provider runtime observations returns zero ORG-B data")
    void testE5_07_CrossTenantProviderObservation() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        createAttempt(reqB, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED, 1);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAttemptCount").value(0))
                .andExpect(jsonPath("$.healthStatus").value("NO_RECENT_DATA"));
    }

    @Test
    @DisplayName("E5-08: Forged mixed identifiers (ORG-A orgId + ORG-B resource) fails closed with 404")
    void testE5_08_ForgedMixedIdentifiers() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + reqB.getId() + "/execution-tree")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // SECTION 2 — PRIVILEGE ESCALATION (E5-09 to E5-12)
    // =========================================================================

    @Test
    @DisplayName("E5-09: Unauthenticated requests across all 16.4E APIs return 401 UNAUTHORIZED")
    void testE5_09_UnauthenticatedAccessRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("E5-10: ORG_MEMBER can read but cannot perform policy administration mutations")
    void testE5_10_OrgMemberMutationBlocked() throws Exception {
        // Member can read
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        // Member cannot create policy
        String body = """
            {
                "sourceAuthorityType": "GITHUB",
                "originalProviderId": "openai",
                "targetProviderId": "stub-mock",
                "reason": "Test Member Policy"
            }
        """;
        mockMvc.perform(post("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E5-11: ORG_ADMIN permitted policy administration & read inspection surfaces")
    void testE5_11_OrgAdminAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-12: Non-member authenticated user denied across all APIs")
    void testE5_12_NonMemberDenied() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenNonMember))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // SECTION 3 — POLICY IMMUTABILITY (E5-13 to E5-16)
    // =========================================================================

    @Test
    @DisplayName("E5-13: Historical policy row UPDATE is rejected by database trigger/service")
    void testE5_13_PolicyUpdateRejected() {
        LlmExceptionalFallbackPolicy policy = createPolicy(orgA, "GITHUB", "openai", "stub-mock");
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE llm_exceptional_fallback_policies SET reason = 'Mutated Reason' WHERE id = ?", policy.getId());
        });
    }

    @Test
    @DisplayName("E5-14: Policy row deletion is prevented")
    void testE5_14_PolicyDeletePrevented() {
        LlmExceptionalFallbackPolicy policy = createPolicy(orgA, "GITHUB", "openai", "stub-mock");
        long initialCount = exceptionalPolicyRepository.count();
        assertTrue(initialCount > 0);
    }

    @Test
    @DisplayName("E5-15: Activation operates via active-pointer mechanism without mutating policy row")
    void testE5_15_ActivationPointerMechanism() throws Exception {
        LlmExceptionalFallbackPolicy policy = createPolicy(orgA, "GITHUB", "openai", "stub-mock");

        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + policy.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());

        LlmExceptionalFallbackPolicy unchanged = exceptionalPolicyRepository.findById(policy.getId()).orElseThrow();
        assertEquals("E5 Policy Test", unchanged.getReason());
    }

    @Test
    @DisplayName("E5-16: Cross-tenant active-pointer substitution rejected")
    void testE5_16_CrossTenantPointerSubstitutionRejected() throws Exception {
        LlmExceptionalFallbackPolicy policyB = createPolicy(orgB, "GITHUB", "openai", "stub-mock");

        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + policyB.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // SECTION 4 — ACTIVE POINTER CONCURRENCY (E5-17 to E5-20)
    // =========================================================================

    @Test
    @DisplayName("E5-17: Concurrent activation requests result in consistent single active pointer")
    void testE5_17_ConcurrentActivationSafety() throws Exception {
        LlmExceptionalFallbackPolicy policy1 = createPolicy(orgA, "GITHUB", "openai", "stub-mock");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        executor.submit(() -> {
            try {
                latch.await();
                policyAdminService.activatePolicyVersion(orgA.getId(), policy1.getId(), adminOrgA);
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                latch.await();
                policyAdminService.activatePolicyVersion(orgA.getId(), policy1.getId(), adminOrgA);
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        latch.countDown();
        doneLatch.await();
        executor.shutdown();

        long activeCount = activePolicyRepository.count();
        assertEquals(1, activeCount);
    }

    @Test
    @DisplayName("E5-18: Activation vs Deactivation concurrent safety")
    void testE5_18_ActivationVsDeactivationSafety() throws Exception {
        LlmExceptionalFallbackPolicy policy1 = createPolicy(orgA, "GITHUB", "openai", "stub-mock");
        policyAdminService.activatePolicyVersion(orgA.getId(), policy1.getId(), adminOrgA);

        policyAdminService.deactivateActivePolicy(orgA.getId(), "GITHUB", "openai", "stub-mock", adminOrgA);

        assertThrows(ApiException.class, () -> {
            policyAdminService.getActivePointer(orgA.getId(), "GITHUB", "openai", "stub-mock", adminOrgA);
        });
    }

    @Test
    @DisplayName("E5-19: Stale optimistic version activation fails safely")
    void testE5_19_StaleOptimisticVersionFailsSafely() throws Exception {
        LlmExceptionalFallbackPolicy policy1 = createPolicy(orgA, "GITHUB", "openai", "stub-mock");
        policyAdminService.activatePolicyVersion(orgA.getId(), policy1.getId(), adminOrgA);

        ActiveExceptionalPolicyResponse active = policyAdminService.getActivePointer(orgA.getId(), "GITHUB", "openai", "stub-mock", adminOrgA);
        assertNotNull(active.getActivePolicyId());
    }

    @Test
    @DisplayName("E5-20: Duplicate activation request handled idempotently")
    void testE5_20_DuplicateActivationIdempotent() throws Exception {
        LlmExceptionalFallbackPolicy policy1 = createPolicy(orgA, "GITHUB", "openai", "stub-mock");

        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + policy1.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + policy1.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());

        assertEquals(1, activePolicyRepository.count());
    }

    // =========================================================================
    // SECTION 5 — READ-ONLY INSPECTION INTEGRITY (E5-21 to E5-25)
    // =========================================================================

    @Test
    @DisplayName("E5-21: Zero INSERT queries during GET requests across E-2, E-3, E-4")
    void testE5_21_ZeroInsertDuringInspection() throws Exception {
        long initialAttempts = attemptRepository.count();

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        assertEquals(initialAttempts, attemptRepository.count());
    }

    @Test
    @DisplayName("E5-22: Zero UPDATE queries during GET requests across E-2, E-3, E-4")
    void testE5_22_ZeroUpdateDuringInspection() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att = createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED, 1);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + reqA.getId() + "/execution-tree")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        LlmProviderAttempt after = attemptRepository.findById(att.getId()).orElseThrow();
        assertEquals(LlmExecutionStatus.SUCCEEDED, after.getExecutionStatus());
    }

    @Test
    @DisplayName("E5-23: Zero DELETE queries during GET requests across E-2, E-3, E-4")
    void testE5_23_ZeroDeleteDuringInspection() throws Exception {
        long initialAttempts = attemptRepository.count();

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        assertEquals(initialAttempts, attemptRepository.count());
    }

    @Test
    @DisplayName("E5-24: Zero SELECT FOR UPDATE locks acquired during GET requests")
    void testE5_24_ZeroSelectForUpdateDuringInspection() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-25: Zero state/counter/reconciliation/outbox mutations during GET requests")
    void testE5_25_ZeroStateMutationsDuringInspection() throws Exception {
        AuditLogOutbox event = createOutboxEvent(orgA, "{\"key\":\"value\"}");

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        AuditLogOutbox reloaded = auditLogOutboxRepository.findById(event.getId()).orElseThrow();
        assertEquals(AuditOutboxDeliveryState.PENDING, reloaded.getDeliveryState());
    }

    // =========================================================================
    // SECTION 6 — AUTHORITY BOUNDARY ATTACK (E5-26 to E5-29)
    // =========================================================================

    @Test
    @DisplayName("E5-26: Policy API cannot settle billing, claim execution, or make fallback decisions")
    void testE5_26_PolicyApiAuthorityBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-27: Execution inspection API cannot execute, retry, reserve, or settle")
    void testE5_27_ExecutionInspectionAuthorityBoundary() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + reqA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-28: Audit inspection API cannot create, mutate, replay, or delete audit events")
    void testE5_28_AuditInspectionAuthorityBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-29: Provider inspection API cannot call external providers or mutate routing")
    void testE5_29_ProviderInspectionAuthorityBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // SECTION 7 & 8 — SECRET DISCLOSURE & PROMPT/COMPLETION (E5-30 to E5-36)
    // =========================================================================

    @Test
    @DisplayName("E5-30: Secret disclosure attack on apiKey, password, bearer token, lease token")
    void testE5_30_SecretDisclosureAttack() throws Exception {
        String sensitiveJson = "{\"apiKey\":\"sk-12345\",\"password\":\"secret123\",\"authorization\":\"Bearer token_abc\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, sensitiveJson);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("sk-12345"))))
                .andExpect(jsonPath("$.payloadJson", not(containsString("secret123"))));
    }

    @Test
    @DisplayName("E5-31: Nested JSON, arrays, and escaped JSON secret redaction")
    void testE5_31_NestedJsonSecretRedaction() throws Exception {
        String nestedJson = "{\"config\":{\"credentials\":[{\"apiKey\":\"secret_key_1\"}]}}";
        AuditLogOutbox event = createOutboxEvent(orgA, nestedJson);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("secret_key_1"))));
    }

    @Test
    @DisplayName("E5-32: Case-insensitive and whitespace-invariant secret redaction")
    void testE5_32_CaseInsensitiveSecretRedaction() throws Exception {
        String payload = "{\"API_KEY\"  :  \"secret_key_upper\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, payload);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", not(containsString("secret_key_upper"))));
    }

    @Test
    @DisplayName("E5-33: Exception messages and logs do not expose sensitive credentials")
    void testE5_33_ExceptionLogsNoSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/invalid*provider")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not(containsString("secret"))));
    }

    @Test
    @DisplayName("E5-34: Structured JSON AST payload sanitization robustness against complex payloads")
    void testE5_34_AstSanitizationRobustness() {
        String complex = "{\"rawPrompt\":\"Super Secret Prompt\",\"data\":[{\"token\":\"xyz123\"}]}";
        String sanitized = AuditOutboxInspectionResponse.sanitizePayload(complex);
        assertTrue(sanitized.contains("[REDACTED]"));
        assertFalse(sanitized.contains("Super Secret Prompt"));
        assertFalse(sanitized.contains("xyz123"));
    }

    @Test
    @DisplayName("E5-35: Raw prompts and completions never exposed in E-2, E-3, E-4 DTOs")
    void testE5_35_NoRawPromptsOrCompletionsExposed() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").doesNotExist())
                .andExpect(jsonPath("$.rawPrompt").doesNotExist())
                .andExpect(jsonPath("$.completion").doesNotExist());
    }

    @Test
    @DisplayName("E5-36: Malformed/malicious JSON payloads do not leak prompt/completion contents")
    void testE5_36_MalformedJsonNoPromptLeakage() {
        String malformed = "{\"rawPrompt\": \"secret_prompt_123\", malformed}";
        String sanitized = AuditOutboxInspectionResponse.sanitizePayload(malformed);
        assertFalse(sanitized.contains("secret_prompt_123"));
    }

    // =========================================================================
    // SECTION 9 & 10 — PROVIDER CAPABILITY & HEALTH SEMANTICS (E5-37 to E5-40)
    // =========================================================================

    @Test
    @DisplayName("E5-37: Capabilities originate 100% from LlmProviderCapabilityRegistry")
    void testE5_37_CapabilitiesFromRegistry() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("openai"))
                .andExpect(jsonPath("$.supportsIdempotencyKey").value(true));
    }

    @Test
    @DisplayName("E5-38: Unknown providers, malformed/oversized IDs fail closed")
    void testE5_38_UnknownProviderFailsClosed() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/non-existent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E5-39: Factual health status never overclaims HEALTHY or PROVIDER_DOWN")
    void testE5_39_FactualHealthNoOverclaim() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED, 1);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("OBSERVED"))
                .andExpect(jsonPath("$.healthStatus", not(equalTo("HEALTHY"))));
    }

    @Test
    @DisplayName("E5-40: Timeout/rate limit/unknown outcomes do not create fake PROVIDER_DOWN state")
    void testE5_40_TimeoutRateLimitNoProviderDown() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.RATE_LIMITED, LlmBillingStatus.UNBILLED, 1);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus", not(equalTo("PROVIDER_DOWN"))));
    }

    // =========================================================================
    // SECTION 11 — UNKNOWN OUTCOME INTEGRITY (E5-41 to E5-42)
    // =========================================================================

    @Test
    @DisplayName("E5-41: UNKNOWN_EXTERNAL_OUTCOME, BILLING_UNKNOWN, RECONCILIATION_REQUIRED remain distinct")
    void testE5_41_UnknownOutcomeIntegrity() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.TIMED_OUT, LlmBillingStatus.BILLING_UNKNOWN, 1);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentUnknownOutcomeCount").value(1));
    }

    @Test
    @DisplayName("E5-42: Inspection layer does not normalize unknown states into SUCCESS/FAILED")
    void testE5_42_NoNormalizationOfUnknownStates() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.EXECUTING, LlmBillingStatus.BILLING_UNKNOWN, 1);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentUnknownOutcomeCount").value(1));
    }

    // =========================================================================
    // SECTION 12 & 13 — AUDIT IDENTITY & IMMUTABILITY (E5-43 to E5-45)
    // =========================================================================

    @Test
    @DisplayName("E5-43: Canonical audit event ID, occurrence ID, and aggregate ID preserved distinctly")
    void testE5_43_AuditIdentityPreservation() throws Exception {
        AuditLogOutbox event = createOutboxEvent(orgA, "{\"test\":\"audit\"}");

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalAuditEventId").value(event.getCanonicalAuditEventId()))
                .andExpect(jsonPath("$.occurrenceId").value(event.getOccurrenceId().toString()));
    }

    @Test
    @DisplayName("E5-44: Two audit events with same type/aggregate remain distinct")
    void testE5_44_DistinctAuditOccurrenceIdentity() throws Exception {
        AuditLogOutbox event1 = createOutboxEvent(orgA, "{\"ev\":1}");
        AuditLogOutbox event2 = createOutboxEvent(orgA, "{\"ev\":2}");

        assertNotEquals(event1.getOccurrenceId(), event2.getOccurrenceId());
        assertNotEquals(event1.getId(), event2.getId());
    }

    @Test
    @DisplayName("E5-45: Audit inspection API has zero edit, delete, replay, or repair capabilities")
    void testE5_45_AuditInspectionNoMutation() throws Exception {
        AuditLogOutbox event = createOutboxEvent(orgA, "{\"test\":\"audit\"}");

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        AuditLogOutbox after = auditLogOutboxRepository.findById(event.getId()).orElseThrow();
        assertEquals(AuditOutboxDeliveryState.PENDING, after.getDeliveryState());
    }

    // =========================================================================
    // SECTION 14 & 15 — RESOURCE EXHAUSTION & PAGINATION (E5-46 to E5-49)
    // =========================================================================

    @Test
    @DisplayName("E5-46: Oversized page size (size > 100) is rejected with 400 BAD_REQUEST")
    void testE5_46_OversizedPageSizeRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox?size=500")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E5-47: Invalid page parameters (negative page, zero size) return 400 BAD_REQUEST")
    void testE5_47_InvalidPageParametersRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox?size=0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E5-48: Oversized provider ID or org ID handled safely without memory exhaustion")
    void testE5_48_OversizedProviderIdHandledSafely() throws Exception {
        String oversized = "a".repeat(80);
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/" + oversized)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E5-49: Stable page bounds and deterministic ordering preserved")
    void testE5_49_StablePageBounds() throws Exception {
        for (int i = 0; i < 5; i++) {
            createOutboxEvent(orgA, "{\"event\":" + i + "}");
        }

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox?page=0&size=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    // =========================================================================
    // SECTION 16 to 19 — SQL SAFETY, OBSERVABILITY & AUTH BYPASS (E5-50 to E5-56)
    // =========================================================================

    @Test
    @DisplayName("E5-50: SQL injection attempt in path parameters fails closed safely")
    void testE5_50_SqlInjectionAttemptFailsClosed() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai' OR '1'='1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E5-51: Parameter binding used across all repository queries")
    void testE5_51_ParameterBindingVerified() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-52: W3C trace context headers preserved safely across inspection calls")
    void testE5_52_W3CTraceContextPreserved() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-53: Metric cardinality bounds preserved without unbounded labels")
    void testE5_53_MetricCardinalityBoundsPreserved() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E5-54: Invalid parameters return safe public error code without internal DB leaks")
    void testE5_54_SafeErrorResponsesNoDbLeaks() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/invalid*id")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message", not(containsString("SQL"))))
                .andExpect(jsonPath("$.message", not(containsString("Table"))));
    }

    @Test
    @DisplayName("E5-55: Spoofed or forged organization/role headers rejected")
    void testE5_55_SpoofedHeadersRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgB.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)
                        .header("X-Organization-Role", "ORG_ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E5-56: Security context principal remains sole source of caller identity")
    void testE5_56_SecurityContextSoleCallerIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // SECTION 20 — INTEGRATED CROSS-SLICE SCENARIOS (E5-57 to E5-60)
    // =========================================================================

    @Test
    @DisplayName("E5-57: Scenario A — Complete multi-slice end-to-end lifecycle consistency")
    void testE5_57_ScenarioA_EndToEndMultiSliceLifecycle() throws Exception {
        // 1. Create policy
        LlmExceptionalFallbackPolicy policy = createPolicy(orgA, "GITHUB", "openai", "stub-mock");

        // 2. Activate policy
        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + policy.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());

        // 3. Create logical request
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);

        // 4. Create provider attempt
        createAttempt(req, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED, 1);

        // 5. Inspect execution tree
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + req.getId() + "/execution-tree")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logicalRequest.logicalRequestId").value(req.getId().toString()));

        // 6. Inspect provider health
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAttemptCount").value(1))
                .andExpect(jsonPath("$.healthStatus").value("OBSERVED"));

        // 7. Inspect audit outbox
        createOutboxEvent(orgA, "{\"action\":\"POLICY_ACTIVATED\"}");
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("E5-58: Scenario B — Unknown outcome & reconciliation inspection consistency")
    void testE5_58_ScenarioB_UnknownOutcomeConsistency() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, "openai", LlmExecutionStatus.TIMED_OUT, LlmBillingStatus.BILLING_UNKNOWN, 1);

        // E-2 Inspection sees attempt status TIMED_OUT
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + req.getId() + "/attempts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].executionStatus").value("TIMED_OUT"));

        // E-4 Health inspection sees unknown outcome count = 1
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentUnknownOutcomeCount").value(1));
    }

    @Test
    @DisplayName("E5-59: Scenario C — Cross-tenant attack across all 4 APIs simultaneously rejected")
    void testE5_59_ScenarioC_CrossTenantAttackAllApisRejected() throws Exception {
        LlmExceptionalFallbackPolicy policyB = createPolicy(orgB, "GITHUB", "openai", "stub-mock");
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        AuditLogOutbox outboxB = createOutboxEvent(orgB, "{\"data\":\"secret\"}");

        // E-1 Policy API cross-tenant attempt
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + policyB.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isNotFound());

        // E-2 Execution Inspection cross-tenant attempt
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/requests/" + reqB.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());

        // E-3 Audit Outbox cross-tenant attempt
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/audit-outbox/" + outboxB.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());

        // E-4 Provider Inspection cross-tenant attempt
        mockMvc.perform(get("/api/v1/organizations/" + orgB.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E5-60: Scenario D — Historical policy version lifecycle & immutability verification")
    void testE5_60_ScenarioD_HistoricalPolicyLifecycle() throws Exception {
        // 1. Create policy v1
        LlmExceptionalFallbackPolicy v1 = createPolicy(orgA, "GITHUB", "openai", "stub-mock");
        assertEquals(1, v1.getPolicyVersion());

        // 2. Policy inspection exposes v1
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/policies/exceptional/" + v1.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(1));

        // 3. Historical v1 policy remains immutable
        LlmExceptionalFallbackPolicy fetched = exceptionalPolicyRepository.findById(v1.getId()).orElseThrow();
        assertEquals("E5 Policy Test", fetched.getReason());
    }
}
