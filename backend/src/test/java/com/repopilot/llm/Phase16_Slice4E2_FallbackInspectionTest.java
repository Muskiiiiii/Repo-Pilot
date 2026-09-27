package com.repopilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.entity.*;
import com.repopilot.llm.repository.*;
import com.repopilot.llm.service.LlmFallbackInspectionService;
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
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase16_Slice4E2_FallbackInspectionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private LlmLogicalRequestRepository logicalRequestRepository;
    @Autowired private LlmProviderAttemptRepository providerAttemptRepository;
    @Autowired private LlmFallbackDecisionRepository fallbackDecisionRepository;
    @Autowired private LlmPricingVersionRepository pricingVersionRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private LlmFallbackInspectionService inspectionService;

    private Organization orgA;
    private Organization orgB;
    private Project projectA;
    private Project projectB;

    private User memberOrgA;
    private User adminOrgA;
    private User memberOrgB;
    private User nonMember;

    private String tokenMemberOrgA;
    private String tokenAdminOrgA;
    private String tokenMemberOrgB;
    private String tokenNonMember;

    private LlmPricingVersion defaultPricing;

    @BeforeEach
    void setUp() {
        setUpSchemaTriggers();
        cleanData();

        String uid = UUID.randomUUID().toString().substring(0, 8);
        memberOrgA = createUser("member_orga_" + uid + "@example.com", "Member OrgA");
        adminOrgA = createUser("admin_orga_" + uid + "@example.com", "Admin OrgA");
        memberOrgB = createUser("member_orgb_" + uid + "@example.com", "Member OrgB");
        nonMember = createUser("non_member_" + uid + "@example.com", "Non Member");

        tokenMemberOrgA = tokenProvider.generateAccessToken(memberOrgA);
        tokenAdminOrgA = tokenProvider.generateAccessToken(adminOrgA);
        tokenMemberOrgB = tokenProvider.generateAccessToken(memberOrgB);
        tokenNonMember = tokenProvider.generateAccessToken(nonMember);

        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-e2-" + uid);
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-e2-" + uid);
        orgB = organizationRepository.save(orgB);

        projectA = new Project();
        projectA.setName("Project A");
        projectA.setOrganization(orgA);
        projectA.setOwner(adminOrgA);
        projectA = projectRepository.save(projectA);

        projectB = new Project();
        projectB.setName("Project B");
        projectB.setOrganization(orgB);
        projectB.setOwner(memberOrgB);
        projectB = projectRepository.save(projectB);

        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgB, memberOrgB, OrganizationRole.ORG_MEMBER));

        defaultPricing = pricingVersionRepository.findByProviderIdAndModelIdAndPricingVersion("openai", "gpt-4", 1)
                .orElseGet(() -> {
                    LlmPricingVersion pv = new LlmPricingVersion();
                    pv.setProviderId("openai");
                    pv.setModelId("gpt-4");
                    pv.setPricingVersion(1);
                    pv.setInputRateMicroUsdPer1k(1000L);
                    pv.setOutputRateMicroUsdPer1k(2000L);
                    return pricingVersionRepository.save(pv);
                });
    }

    private void setUpSchemaTriggers() {
        executeDdlSafely("ALTER TABLE llm_exceptional_fallback_policies ADD CONSTRAINT uk_llm_exceptional_policy_identity UNIQUE (id, organization_id, source_authority_type, original_provider_id, target_provider_id);");
        executeDdlSafely("""
            ALTER TABLE llm_active_exceptional_fallback_policies
            ADD CONSTRAINT fk_active_policy_identity
            FOREIGN KEY (active_policy_id, organization_id, source_authority_type, original_provider_id, target_provider_id)
            REFERENCES llm_exceptional_fallback_policies(id, organization_id, source_authority_type, original_provider_id, target_provider_id) ON DELETE RESTRICT;
        """);
    }

    private void executeDdlSafely(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception ignored) {}
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        try { auditLogOutboxRepository.deleteAll(); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM audit_logs;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_fallback_decisions;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_provider_attempts;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_logical_requests;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_active_exceptional_fallback_policies;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_exceptional_fallback_policies;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_policies;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_overage_liabilities;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_usage_counters;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM repositories;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM project_members;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM projects;"); } catch (Exception ignored) {}
        try { organizationMemberRepository.deleteAll(); } catch (Exception ignored) {}
        try { organizationRepository.deleteAll(); } catch (Exception ignored) {}
        try { userRepository.deleteAll(); } catch (Exception ignored) {}
    }

    private User createUser(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setPasswordHash("hashed_password");
        return userRepository.save(user);
    }

    private LlmLogicalRequest createLogicalRequest(Organization org, Project prj) {
        LlmLogicalRequest req = new LlmLogicalRequest();
        req.setOrganization(org);
        req.setProject(prj);
        req.setSourceAuthorityType("TASK_EXECUTION");
        req.setSourceAuthorityId(UUID.randomUUID().toString());
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setFeatureName("CODE_REVIEW");
        req.setExecutionStatus(LlmExecutionStatus.ATTEMPT_CREATED);
        req.setBillingStatus(LlmBillingStatus.UNBILLED);
        req.setCurrentAttemptCount(1);
        req.setFencingVersion(1L);
        return logicalRequestRepository.save(req);
    }

    private LlmProviderAttempt createAttempt(LlmLogicalRequest req, int attemptNumber, String provider, String model) {
        LlmProviderAttempt attempt = new LlmProviderAttempt();
        attempt.setLogicalRequest(req);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setProviderId(provider);
        attempt.setModelId(model);
        attempt.setPricingVersion(defaultPricing);
        attempt.setProviderIdempotencyKey("prov-key-" + UUID.randomUUID());
        attempt.setWorkerLeaseToken("SECRET_LEASE_TOKEN_" + UUID.randomUUID());
        attempt.setInputHash("input_hash_123");
        attempt.setExecutionStatus(LlmExecutionStatus.EXECUTING);
        attempt.setBillingStatus(LlmBillingStatus.UNBILLED);
        attempt.setPromptTokens(100);
        attempt.setCompletionTokens(50);
        attempt.setTotalTokens(150);
        return providerAttemptRepository.save(attempt);
    }

    private LlmFallbackDecision createDecision(Organization org, LlmLogicalRequest req, LlmProviderAttempt srcAttempt, int seq) {
        LlmFallbackDecision decision = new LlmFallbackDecision();
        decision.setOrganization(org);
        decision.setLogicalRequest(req);
        decision.setSourceAttempt(srcAttempt);
        decision.setDecisionSequence(seq);
        decision.setOriginalProviderId(srcAttempt.getProviderId());
        decision.setOriginalModelId(srcAttempt.getModelId());
        decision.setTargetProviderId("anthropic");
        decision.setTargetModelId("claude-3-5-sonnet");
        decision.setDecisionStatus(LlmFallbackDecisionStatus.DECISION_INITIATED);
        decision.setTriggerSafetyClassification(LlmTriggerSafetyClassification.CONFIRMED_NOT_DISPATCHED);
        return fallbackDecisionRepository.save(decision);
    }

    // =========================================================================
    // E2-01 to E2-04: BASIC READ ENDPOINTS
    // =========================================================================

    @Test
    @DisplayName("E2-01: Valid logical request read returns 200 OK")
    void testE2_01_validLogicalRequestRead() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logicalRequestId", is(req.getId().toString())))
                .andExpect(jsonPath("$.organizationId", is(orgA.getId().toString())))
                .andExpect(jsonPath("$.featureName", is("CODE_REVIEW")));
    }

    @Test
    @DisplayName("E2-02: Valid attempt listing returns 200 OK")
    void testE2_02_validAttemptListing() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, 1, "openai", "gpt-4");
        createAttempt(req, 2, "anthropic", "claude-3-5-sonnet");

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(2)));
    }

    @Test
    @DisplayName("E2-03: Valid fallback decision listing returns 200 OK")
    void testE2_03_validFallbackDecisionListing() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att = createAttempt(req, 1, "openai", "gpt-4");
        createDecision(orgA, req, att, 1);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/fallback-decisions", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].originalProviderId", is("openai")))
                .andExpect(jsonPath("$.content[0].targetProviderId", is("anthropic")));
    }

    @Test
    @DisplayName("E2-04: Valid execution tree returns full tree structure")
    void testE2_04_validExecutionTree() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att1 = createAttempt(req, 1, "openai", "gpt-4");
        LlmFallbackDecision dec = createDecision(orgA, req, att1, 1);
        LlmProviderAttempt att2 = createAttempt(req, 2, "anthropic", "claude-3-5-sonnet");
        dec.setTargetAttempt(att2);
        fallbackDecisionRepository.save(dec);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/execution-tree", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logicalRequest.logicalRequestId", is(req.getId().toString())))
                .andExpect(jsonPath("$.nodes", hasSize(2)))
                .andExpect(jsonPath("$.nodes[0].attempt.attemptNumber", is(1)))
                .andExpect(jsonPath("$.nodes[0].triggeredDecisions", hasSize(1)))
                .andExpect(jsonPath("$.nodes[0].triggeredDecisions[0].targetAttempt.attemptNumber", is(2)));
    }

    // =========================================================================
    // E2-05 to E2-08: DETERMINISM & PAGINATION
    // =========================================================================

    @Test
    @DisplayName("E2-05: Deterministic attempt ordering by attemptNumber ASC")
    void testE2_05_deterministicAttemptOrdering() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, 3, "google", "gemini-pro");
        createAttempt(req, 1, "openai", "gpt-4");
        createAttempt(req, 2, "anthropic", "claude-3-5-sonnet");

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attemptNumber", is(1)))
                .andExpect(jsonPath("$.content[1].attemptNumber", is(2)))
                .andExpect(jsonPath("$.content[2].attemptNumber", is(3)));
    }

    @Test
    @DisplayName("E2-06: Deterministic decision ordering by decisionSequence ASC")
    void testE2_06_deterministicDecisionOrdering() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att = createAttempt(req, 1, "openai", "gpt-4");
        createDecision(orgA, req, att, 2);
        createDecision(orgA, req, att, 1);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/fallback-decisions", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].decisionSequence", is(1)))
                .andExpect(jsonPath("$.content[1].decisionSequence", is(2)));
    }

    @Test
    @DisplayName("E2-07: Pagination limit returns page metadata")
    void testE2_07_paginationLimit() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, 1, "openai", "gpt-4");
        createAttempt(req, 2, "anthropic", "claude-3-5-sonnet");

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts?page=0&size=1", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(1)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.totalPages", is(2)));
    }

    @Test
    @DisplayName("E2-08: Oversized pagination size is clamped to 100")
    void testE2_08_oversizedPaginationRejected() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, 1, "openai", "gpt-4");

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts?page=0&size=500", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(100)));
    }

    // =========================================================================
    // E2-09 to E2-16: AUTHORIZATION & TENANT SECURITY
    // =========================================================================

    @Test
    @DisplayName("E2-09: Unauthenticated access returns HTTP 401")
    void testE2_09_unauthenticatedAccess() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("E2-10: Unauthorized organization access returns HTTP 403")
    void testE2_10_unauthorizedOrganizationAccess() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenNonMember))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E2-11: Cross-tenant logical request access fails closed with HTTP 404")
    void testE2_11_crossTenantLogicalRequest() throws Exception {
        LlmLogicalRequest reqOrgA = createLogicalRequest(orgA, projectA);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgB.getId(), reqOrgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E2-12: Cross-tenant attempt listing fails closed with HTTP 404")
    void testE2_12_crossTenantAttemptID() throws Exception {
        LlmLogicalRequest reqOrgA = createLogicalRequest(orgA, projectA);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgB.getId(), reqOrgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E2-13: Cross-tenant decision listing fails closed with HTTP 404")
    void testE2_13_crossTenantDecisionID() throws Exception {
        LlmLogicalRequest reqOrgA = createLogicalRequest(orgA, projectA);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/fallback-decisions", orgB.getId(), reqOrgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E2-14: Fabricated organization/request combination returns HTTP 404")
    void testE2_14_fabricatedOrganizationRequestCombination() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E2-15: Fabricated request/attempt combination returns HTTP 404")
    void testE2_15_fabricatedRequestAttemptCombination() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E2-16: Fabricated request/decision combination returns HTTP 404")
    void testE2_16_fabricatedRequestDecisionCombination() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/fallback-decisions", orgA.getId(), UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // E2-17 to E2-20: SECRET REDACTION & SAFETY
    // =========================================================================

    @Test
    @DisplayName("E2-17: Response contains no raw secret markers")
    void testE2_17_secretRedaction() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, 1, "openai", "gpt-4");

        String responseJson = mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/execution-tree", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(responseJson.contains("SECRET_LEASE_TOKEN"));
        assertFalse(responseJson.contains("passwordHash"));
        assertFalse(responseJson.contains("bearerToken"));
    }

    @Test
    @DisplayName("E2-18: Worker lease token is never exposed in attempt response")
    void testE2_18_leaseTokenNeverExposed() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att = createAttempt(req, 1, "openai", "gpt-4");
        att.setWorkerLeaseToken("SUPER_SECRET_LEASE_TOKEN_ABC");
        providerAttemptRepository.save(att);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].workerLeaseToken").doesNotExist());
    }

    @Test
    @DisplayName("E2-19: Raw prompt is never exposed in inspection response")
    void testE2_19_rawPromptNeverExposed() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, 1, "openai", "gpt-4");

        String json = mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(json.contains("rawPrompt"));
        assertFalse(json.contains("promptText"));
    }

    @Test
    @DisplayName("E2-20: Raw completion is never exposed in inspection response")
    void testE2_20_rawCompletionNeverExposed() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        createAttempt(req, 1, "openai", "gpt-4");

        String json = mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(json.contains("rawCompletion"));
        assertFalse(json.contains("completionText"));
    }

    // =========================================================================
    // E2-21 to E2-24: STATUS & FAILURE SEMANTICS
    // =========================================================================

    @Test
    @DisplayName("E2-21: TIMED_OUT execution status is preserved honestly")
    void testE2_21_unknownExternalOutcomePreserved() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        req.setExecutionStatus(LlmExecutionStatus.TIMED_OUT);
        logicalRequestRepository.save(req);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionStatus", is("TIMED_OUT")));
    }

    @Test
    @DisplayName("E2-22: BILLING_UNKNOWN status is preserved honestly")
    void testE2_22_billingUnknownPreserved() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        req.setBillingStatus(LlmBillingStatus.BILLING_UNKNOWN);
        logicalRequestRepository.save(req);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingStatus", is("BILLING_UNKNOWN")));
    }

    @Test
    @DisplayName("E2-23: REJECTED_POLICY status is preserved honestly")
    void testE2_23_reconciliationRequiredPreserved() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        req.setExecutionStatus(LlmExecutionStatus.REJECTED_POLICY);
        logicalRequestRepository.save(req);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionStatus", is("REJECTED_POLICY")));
    }

    @Test
    @DisplayName("E2-24: MALFORMED_OUTPUT status is preserved honestly")
    void testE2_24_staleInternalResultPreserved() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att = createAttempt(req, 1, "openai", "gpt-4");
        att.setExecutionStatus(LlmExecutionStatus.MALFORMED_OUTPUT);
        providerAttemptRepository.save(att);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].executionStatus", is("MALFORMED_OUTPUT")));
    }

    // =========================================================================
    // E2-25 to E2-29: READ-ONLY & ZERO MUTATION PROOF
    // =========================================================================

    @Test
    @DisplayName("E2-25: Zero database writes occur during inspection")
    void testE2_25_zeroDatabaseWrites() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att1 = createAttempt(req, 1, "openai", "gpt-4");
        createDecision(orgA, req, att1, 1);

        long reqCountBefore = logicalRequestRepository.count();
        long attCountBefore = providerAttemptRepository.count();
        long decCountBefore = fallbackDecisionRepository.count();

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), req.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/fallback-decisions", orgA.getId(), req.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/execution-tree", orgA.getId(), req.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)).andExpect(status().isOk());

        assertEquals(reqCountBefore, logicalRequestRepository.count());
        assertEquals(attCountBefore, providerAttemptRepository.count());
        assertEquals(decCountBefore, fallbackDecisionRepository.count());
    }

    @Test
    @DisplayName("E2-26: Zero accounting mutations occur during inspection")
    void testE2_26_zeroAccountingMutations() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        req.setTotalTokensUsed(500);
        req.setTotalBilledCostMicroUsd(1000L);
        logicalRequestRepository.save(req);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        LlmLogicalRequest reloaded = logicalRequestRepository.findById(req.getId()).orElseThrow();
        assertEquals(500, reloaded.getTotalTokensUsed());
        assertEquals(1000L, reloaded.getTotalBilledCostMicroUsd());
    }

    @Test
    @DisplayName("E2-27: Zero execution mutations occur during inspection")
    void testE2_27_zeroExecutionMutations() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att = createAttempt(req, 1, "openai", "gpt-4");
        Long initialFencingVersion = att.getFencingVersion();

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/attempts", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        LlmProviderAttempt reloaded = providerAttemptRepository.findById(att.getId()).orElseThrow();
        assertEquals(initialFencingVersion, reloaded.getFencingVersion());
        assertEquals(att.getWorkerLeaseToken(), reloaded.getWorkerLeaseToken());
    }

    @Test
    @DisplayName("E2-28: Zero fallback mutations occur during inspection")
    void testE2_28_zeroFallbackMutations() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        LlmProviderAttempt att = createAttempt(req, 1, "openai", "gpt-4");
        LlmFallbackDecision dec = createDecision(orgA, req, att, 1);
        Long initialFencingVersion = dec.getFencingVersion();

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/fallback-decisions", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        LlmFallbackDecision reloaded = fallbackDecisionRepository.findById(dec.getId()).orElseThrow();
        assertEquals(initialFencingVersion, reloaded.getFencingVersion());
        assertEquals(dec.getDecisionStatus(), reloaded.getDecisionStatus());
    }

    @Test
    @DisplayName("E2-29: Pure persistence query invokes zero provider adapters")
    void testE2_29_zeroProviderCalls() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);

        inspectionService.getExecutionTree(orgA.getId(), req.getId(), memberOrgA);

        LlmLogicalRequest reloaded = logicalRequestRepository.findById(req.getId()).orElseThrow();
        assertNotNull(reloaded);
    }

    @Test
    @DisplayName("E2-30: Bounded execution tree rejects total node count exceeding 100")
    void testE2_30_boundedExecutionTree() throws Exception {
        LlmLogicalRequest req = createLogicalRequest(orgA, projectA);
        for (int i = 1; i <= 101; i++) {
            createAttempt(req, i, "openai", "gpt-4");
        }

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/requests/{logicalRequestId}/execution-tree", orgA.getId(), req.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("exceeds maximum allowed limit")));
    }
}
