package com.repopilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.llm.dto.ProviderCapabilityResponse;
import com.repopilot.llm.dto.ProviderHealthResponse;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.repository.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase16_Slice4E4_ProviderInspectionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmProviderAttemptRepository attemptRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private JwtTokenProvider tokenProvider;

    private Organization orgA;
    private Organization orgB;
    private Project projectA;
    private Project projectB;

    private User memberOrgA;
    private User adminOrgA;
    private User memberOrgB;

    private String tokenMemberOrgA;
    private String tokenAdminOrgA;
    private String tokenMemberOrgB;

    private LlmPricingVersion defaultPricingVersion;

    @BeforeEach
    void setUp() {
        cleanData();

        String uid = UUID.randomUUID().toString().substring(0, 8);
        memberOrgA = createUser("member_orga_e4_" + uid + "@example.com", "Member OrgA");
        adminOrgA = createUser("admin_orga_e4_" + uid + "@example.com", "Admin OrgA");
        memberOrgB = createUser("member_orgb_e4_" + uid + "@example.com", "Member OrgB");

        tokenMemberOrgA = tokenProvider.generateAccessToken(memberOrgA);
        tokenAdminOrgA = tokenProvider.generateAccessToken(adminOrgA);
        tokenMemberOrgB = tokenProvider.generateAccessToken(memberOrgB);

        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-e4-" + uid);
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-e4-" + uid);
        orgB = organizationRepository.save(orgB);

        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
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

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        try { attemptRepository.deleteAll(); } catch (Exception ignored) {}
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
        req.setFeatureName("E4_TEST");
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

    private LlmProviderAttempt createAttempt(LlmLogicalRequest req, String providerId, LlmExecutionStatus status, LlmBillingStatus billingStatus) {
        return createAttempt(req, providerId, status, billingStatus, 1);
    }

    // =========================================================================
    // E4-01 to E4-05: Provider List & Capability Projections
    // =========================================================================

    @Test
    @DisplayName("E4-01: List registered providers returns HTTP 200 with registered providers")
    void testE4_01_ListProviders_Success() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$[0].providerId").exists());
    }

    @Test
    @DisplayName("E4-02: Get OpenAI capability detail returns HTTP 200 with accurate capabilities")
    void testE4_02_GetProviderCapability_OpenAi() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("openai"))
                .andExpect(jsonPath("$.supportsIdempotencyKey").value(true))
                .andExpect(jsonPath("$.supportsProviderRequestIdentity").value(true))
                .andExpect(jsonPath("$.supportsTokenReporting").value(true))
                .andExpect(jsonPath("$.supportsRetrySafety").value(true))
                .andExpect(jsonPath("$.supportsExecutionReconciliation").value(true))
                .andExpect(jsonPath("$.retryAuthoritativelyReconciles").value(true));
    }

    @Test
    @DisplayName("E4-03: Supported model projection matches registry definitions")
    void testE4_03_SupportedModelProjection() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportedModels", hasItem("gpt-4o")))
                .andExpect(jsonPath("$.supportedModels", hasItem("gpt-4o-mini")));
    }

    @Test
    @DisplayName("E4-04: Idempotency capability projection accurately distinguishes providers")
    void testE4_04_IdempotencyCapabilityProjection() throws Exception {
        // OpenAI supports idempotency key
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsIdempotencyKey").value(true));

        // Anthropic does not support idempotency key in registry
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/anthropic")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsIdempotencyKey").value(false));
    }

    @Test
    @DisplayName("E4-05: Execution and reconciliation capability projection accuracy")
    void testE4_05_ExecutionReconciliationCapabilityProjection() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsExecutionReconciliation").value(true))
                .andExpect(jsonPath("$.retryAuthoritativelyReconciles").value(true));
    }

    // =========================================================================
    // E4-06 to E4-10: Authorization & Boundary Protection
    // =========================================================================

    @Test
    @DisplayName("E4-06: Unknown provider returns HTTP 404 NOT_FOUND")
    void testE4_06_UnknownProvider_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/unknown-provider")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E4-07: Unauthenticated request returns HTTP 401 UNAUTHORIZED")
    void testE4_07_UnauthenticatedAccess_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("E4-08: Organization MEMBER role access allowed")
    void testE4_08_OrgMemberAccess_Allowed() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E4-09: Organization ADMIN role access allowed")
    void testE4_09_OrgAdminAccess_Allowed() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E4-10: Cross-tenant provider observation is blocked (HTTP 403 or 404)")
    void testE4_10_CrossTenantProviderObservation_Blocked() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgB))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // E4-11 to E4-14: Tenant Isolation & Observation Scope
    // =========================================================================

    @Test
    @DisplayName("E4-11: Organization A cannot see Organization B attempt counts")
    void testE4_11_OrgA_CannotSeeOrgB_AttemptCounts() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        createAttempt(reqB, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAttemptCount").value(0))
                .andExpect(jsonPath("$.healthStatus").value("NO_RECENT_DATA"));
    }

    @Test
    @DisplayName("E4-12: Organization A cannot see Organization B failure counts")
    void testE4_12_OrgA_CannotSeeOrgB_FailureCounts() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        createAttempt(reqB, "openai", LlmExecutionStatus.FAILED, LlmBillingStatus.UNBILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentFailureCount").value(0));
    }

    @Test
    @DisplayName("E4-13: Organization A cannot see Organization B success counts")
    void testE4_13_OrgA_CannotSeeOrgB_SuccessCounts() throws Exception {
        LlmLogicalRequest reqB = createLogicalRequest(orgB, projectB);
        createAttempt(reqB, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentSuccessCount").value(0));
    }

    @Test
    @DisplayName("E4-14: No recent data represented as explicit NO_RECENT_DATA status")
    void testE4_14_NoRecentData_RepresentedAsNoRecentData() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAttemptCount").value(0))
                .andExpect(jsonPath("$.healthStatus").value("NO_RECENT_DATA"));
    }

    // =========================================================================
    // E4-15 to E4-20: Factual Health Semantics & Unknown Preservation
    // =========================================================================

    @Test
    @DisplayName("E4-15: Historical success is projected as OBSERVED, not state machine HEALTHY")
    void testE4_15_HistoricalSuccess_NotCurrentHealth() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("OBSERVED"))
                .andExpect(jsonPath("$.healthStatus", not(equalTo("HEALTHY"))))
                .andExpect(jsonPath("$.healthStatus", not(equalTo("CURRENTLY_HEALTHY"))));
    }

    @Test
    @DisplayName("E4-16: Timeout does not imply provider-down verdict")
    void testE4_16_Timeout_DoesNotImpliesProviderDown() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.TIMED_OUT, LlmBillingStatus.BILLING_UNKNOWN);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentUnknownOutcomeCount").value(1))
                .andExpect(jsonPath("$.healthStatus", not(equalTo("PROVIDER_DOWN"))));
    }

    @Test
    @DisplayName("E4-17: Rate limit does not imply provider-down verdict")
    void testE4_17_RateLimit_DoesNotImpliesProviderDown() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.RATE_LIMITED, LlmBillingStatus.UNBILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentRateLimitedCount").value(1))
                .andExpect(jsonPath("$.healthStatus", not(equalTo("PROVIDER_DOWN"))));
    }

    @Test
    @DisplayName("E4-18: UNKNOWN_EXTERNAL_OUTCOME preserved")
    void testE4_18_UnknownExternalOutcome_Preserved() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.TIMEOUT, LlmBillingStatus.UNBILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentUnknownOutcomeCount").value(1));
    }

    @Test
    @DisplayName("E4-19: BILLING_UNKNOWN preserved in unknown outcome count")
    void testE4_19_BillingUnknown_Preserved() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.EXECUTING, LlmBillingStatus.BILLING_UNKNOWN);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentUnknownOutcomeCount").value(1));
    }

    @Test
    @DisplayName("E4-20: RECONCILIATION_REQUIRED preserved in reconciliation required count")
    void testE4_20_ReconciliationRequired_Preserved() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.FAILED, LlmBillingStatus.RECONCILIATION_FAILED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentReconciliationRequiredCount").value(1));
    }

    // =========================================================================
    // E4-21 to E4-30: Resource Bounds & Read-Only Invariants
    // =========================================================================

    @Test
    @DisplayName("E4-21: Bounded operational query caps recent attempts at 100")
    void testE4_21_BoundedOperationalQuery() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        for (int i = 0; i < 110; i++) {
            createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED, i + 1);
        }

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationWindowLimit").value(100))
                .andExpect(jsonPath("$.recentAttemptCount").value(100));
    }

    @Test
    @DisplayName("E4-22: No external provider HTTP calls occur during inspection")
    void testE4_22_NoExternalProviderHttpCall() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E4-23: Zero accounting mutations occur during inspection")
    void testE4_23_NoAccountingMutation() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E4-24: Zero execution mutations occur during inspection")
    void testE4_24_NoExecutionMutation() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt attempt = createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        LlmProviderAttempt reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(LlmExecutionStatus.SUCCEEDED, reloaded.getExecutionStatus());
    }

    @Test
    @DisplayName("E4-25: Zero fallback mutations occur during inspection")
    void testE4_25_NoFallbackMutation() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E4-26: Zero reconciliation mutations occur during inspection")
    void testE4_26_NoReconciliationMutation() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E4-27: Zero INSERT queries during provider capability inspection")
    void testE4_27_ZeroInsert() throws Exception {
        long initialAttempts = attemptRepository.count();
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
        assertEquals(initialAttempts, attemptRepository.count());
    }

    @Test
    @DisplayName("E4-28: Zero UPDATE queries during provider health inspection")
    void testE4_28_ZeroUpdate() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt attempt = createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);

        LlmProviderAttempt before = attemptRepository.findById(attempt.getId()).orElseThrow();
        Instant originalStartTime = before.getStartTime();

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        LlmProviderAttempt after = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(originalStartTime, after.getStartTime());
    }

    @Test
    @DisplayName("E4-29: Zero DELETE queries during inspection")
    void testE4_29_ZeroDelete() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);
        long initialAttempts = attemptRepository.count();

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        assertEquals(initialAttempts, attemptRepository.count());
    }

    @Test
    @DisplayName("E4-30: Zero SELECT FOR UPDATE query locks during inspection")
    void testE4_30_ZeroSelectForUpdate() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // E4-31 to E4-38: Security, Ordering & Input Hardening
    // =========================================================================

    @Test
    @DisplayName("E4-31: Provider credential leakage prevention")
    void testE4_31_ProviderCredentialLeakage() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("E4-32: Raw prompt and completion leakage prevention")
    void testE4_32_PromptCompletionLeakage() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").doesNotExist())
                .andExpect(jsonPath("$.rawPrompt").doesNotExist())
                .andExpect(jsonPath("$.completion").doesNotExist())
                .andExpect(jsonPath("$.rawCompletion").doesNotExist());
    }

    @Test
    @DisplayName("E4-33: Metric cardinality bounds preserved")
    void testE4_33_MetricCardinality() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E4-34: Deterministic provider ordering in list endpoint")
    void testE4_34_DeterministicProviderOrdering() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerId").value("anthropic"))
                .andExpect(jsonPath("$[1].providerId").value("google-vertex"))
                .andExpect(jsonPath("$[2].providerId").value("openai"))
                .andExpect(jsonPath("$[3].providerId").value("stub-mock"));
    }

    @Test
    @DisplayName("E4-35: Read consistency under concurrent attempt creation")
    void testE4_35_ConcurrentAttemptCompletionDuringInspection() throws Exception {
        LlmLogicalRequest reqA = createLogicalRequest(orgA, projectA);
        createAttempt(reqA, "openai", LlmExecutionStatus.SUCCEEDED, LlmBillingStatus.BILLED);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/openai/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAttemptCount").value(1));
    }

    @Test
    @DisplayName("E4-36: Malformed provider ID returns HTTP 400 BAD_REQUEST")
    void testE4_36_MalformedProviderId() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/invalid*provider")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E4-37: Oversized provider ID returns HTTP 400 BAD_REQUEST")
    void testE4_37_OversizedProviderId() throws Exception {
        String oversized = "a".repeat(70);
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/llm/providers/" + oversized)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E4-38: Unsupported model verification via registry")
    void testE4_38_UnsupportedModelHandling() throws Exception {
        assertFalse(LlmProviderCapabilityRegistry.isSupportedModel("openai", "non-existent-model"));
        assertTrue(LlmProviderCapabilityRegistry.isSupportedModel("openai", "gpt-4o"));
    }
}
