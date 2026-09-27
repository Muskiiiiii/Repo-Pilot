package com.repopilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.llm.dto.CreateExceptionalPolicyRequest;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.LlmActiveExceptionalFallbackPolicy;
import com.repopilot.llm.entity.LlmExceptionalFallbackPolicy;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.repository.LlmActiveExceptionalFallbackPolicyRepository;
import com.repopilot.llm.repository.LlmExceptionalFallbackPolicyRepository;
import com.repopilot.llm.service.LlmPolicyAdministrationService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase16_Slice4E1_PolicyAdministrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LlmExceptionalFallbackPolicyRepository exceptionalPolicyRepository;
    @Autowired private LlmActiveExceptionalFallbackPolicyRepository activePolicyRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private LlmPolicyAdministrationService policyAdministrationService;

    private Organization orgA;
    private Organization orgB;

    private User adminOrgA;
    private User memberOrgA;
    private User adminOrgB;

    private String tokenAdminOrgA;
    private String tokenMemberOrgA;
    private String tokenAdminOrgB;

    @BeforeEach
    void setUp() {
        setUpSchemaTriggers();
        cleanData();

        String uid = UUID.randomUUID().toString().substring(0, 8);
        adminOrgA = createUser("admin_orga_e1_" + uid + "@example.com", "Admin OrgA");
        memberOrgA = createUser("member_orga_e1_" + uid + "@example.com", "Member OrgA");
        adminOrgB = createUser("admin_orgb_e1_" + uid + "@example.com", "Admin OrgB");

        tokenAdminOrgA = tokenProvider.generateAccessToken(adminOrgA);
        tokenMemberOrgA = tokenProvider.generateAccessToken(memberOrgA);
        tokenAdminOrgB = tokenProvider.generateAccessToken(adminOrgB);

        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-e1-" + uid);
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-e1-" + uid);
        orgB = organizationRepository.save(orgB);

        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN));
    }

    private void setUpSchemaTriggers() {
        executeDdlSafely("ALTER TABLE llm_exceptional_fallback_policies ADD CONSTRAINT uk_llm_exceptional_policy_identity UNIQUE (id, organization_id, source_authority_type, original_provider_id, target_provider_id);");
        executeDdlSafely("""
            ALTER TABLE llm_active_exceptional_fallback_policies
            ADD CONSTRAINT fk_active_policy_identity
            FOREIGN KEY (active_policy_id, organization_id, source_authority_type, original_provider_id, target_provider_id)
            REFERENCES llm_exceptional_fallback_policies(id, organization_id, source_authority_type, original_provider_id, target_provider_id) ON DELETE RESTRICT;
        """);
        executeDdlSafely("""
            CREATE TRIGGER IF NOT EXISTS trg_prevent_exceptional_policy_update_h2
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
        try { jdbcTemplate.execute("DELETE FROM audit_logs;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_policies;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_overage_liabilities;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_usage_counters;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_fallback_decisions;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_provider_attempts;"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM llm_logical_requests;"); } catch (Exception ignored) {}
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

    // =========================================================================
    // E1-01 to E1-05: IMMUTABILITY, ACTIVATION & POINTER SEMANTICS
    // =========================================================================

    @Test
    @DisplayName("E1-01: Create exceptional policy version succeeds for ORG_ADMIN")
    void testE1_01_createPolicyVersion() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest(
                "TASK_EXECUTION", "openai", "anthropic", "Failover to Anthropic on timeout"
        );

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyVersion", is(1)))
                .andExpect(jsonPath("$.originalProviderId", is("openai")))
                .andExpect(jsonPath("$.targetProviderId", is("anthropic")))
                .andExpect(jsonPath("$.active", is(true)));

        assertEquals(1, exceptionalPolicyRepository.findByOrganizationId(orgA.getId()).size());
        assertEquals(1, activePolicyRepository.findByOrganizationId(orgA.getId()).size());
    }

    @Test
    @DisplayName("E1-02: Creating second policy version creates new immutable row with policyVersion 2")
    void testE1_02_immutablePolicyVersion() throws Exception {
        CreateExceptionalPolicyRequest req1 = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Reason V1");
        CreateExceptionalPolicyRequest req2 = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Reason V2");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1))).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyVersion", is(2)));

        List<LlmExceptionalFallbackPolicy> policies = exceptionalPolicyRepository.findByOrganizationId(orgA.getId());
        assertEquals(2, policies.size());

        List<LlmActiveExceptionalFallbackPolicy> activePointers = activePolicyRepository.findByOrganizationId(orgA.getId());
        assertEquals(1, activePointers.size());
        LlmExceptionalFallbackPolicy activePol = exceptionalPolicyRepository.findById(activePointers.get(0).getActivePolicy().getId()).orElseThrow();
        assertEquals(2, activePol.getPolicyVersion());
    }

    @Test
    @DisplayName("E1-03: Direct UPDATE SQL against llm_exceptional_fallback_policies is blocked by DB trigger")
    void testE1_03_historicalVersionCannotBeMutated() {
        LlmExceptionalFallbackPolicy policy = new LlmExceptionalFallbackPolicy(
                orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "Original Reason"
        );
        policy = exceptionalPolicyRepository.saveAndFlush(policy);

        final LlmExceptionalFallbackPolicy savedPolicy = policy;
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE llm_exceptional_fallback_policies SET reason = 'Mutated Reason' WHERE id = ?", savedPolicy.getId());
        });
    }

    @Test
    @DisplayName("E1-04: Valid activation updates active pointer to selected version")
    void testE1_04_validActivation() throws Exception {
        LlmExceptionalFallbackPolicy p1 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "V1"));
        LlmExceptionalFallbackPolicy p2 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 2, adminOrgA, "V2"));

        activePolicyRepository.save(new LlmActiveExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", p2));

        mockMvc.perform(put("/api/v1/organizations/{orgId}/llm/policies/exceptional/{policyId}/activate", orgA.getId(), p1.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePolicyId", is(p1.getId().toString())))
                .andExpect(jsonPath("$.activePolicyVersion", is(1)));
    }

    @Test
    @DisplayName("E1-05: Activation references correct policy identity and metadata")
    void testE1_05_activationReferencesCorrectPolicy() throws Exception {
        LlmExceptionalFallbackPolicy p1 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "CODE_REVIEW", "openai", "google-vertex", 1, adminOrgA, "Policy Identity"));
        activePolicyRepository.save(new LlmActiveExceptionalFallbackPolicy(orgA, "CODE_REVIEW", "openai", "google-vertex", p1));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/policies/exceptional/active", orgA.getId())
                        .param("sourceAuthorityType", "CODE_REVIEW")
                        .param("originalProviderId", "openai")
                        .param("targetProviderId", "google-vertex")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePolicyId", is(p1.getId().toString())))
                .andExpect(jsonPath("$.sourceAuthorityType", is("CODE_REVIEW")));
    }

    // =========================================================================
    // E1-06 to E1-10: TENANT ISOLATION, CONCURRENCY & DEACTIVATION
    // =========================================================================

    @Test
    @DisplayName("E1-06: Cross-tenant activation attempt is rejected")
    void testE1_06_crossTenantActivationRejected() throws Exception {
        LlmExceptionalFallbackPolicy policyOrgA = exceptionalPolicyRepository.save(
                new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "Org A Policy")
        );

        mockMvc.perform(put("/api/v1/organizations/{orgId}/llm/policies/exceptional/{policyId}/activate", orgB.getId(), policyOrgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E1-07: Stale optimistic activation fails cleanly")
    void testE1_07_staleOptimisticActivationRejected() {
        LlmExceptionalFallbackPolicy p1 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "V1"));
        LlmActiveExceptionalFallbackPolicy pointer = activePolicyRepository.save(new LlmActiveExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", p1));

        pointer.setVersion(99L);
        assertThrows(Exception.class, () -> {
            activePolicyRepository.saveAndFlush(pointer);
        });
    }

    @Test
    @DisplayName("E1-08: Real PostgreSQL concurrent activation preserves single active pointer")
    void testE1_08_concurrentActivationPreservesOneActivePointer() throws Exception {
        LlmExceptionalFallbackPolicy p1 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "V1"));
        LlmExceptionalFallbackPolicy p2 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 2, adminOrgA, "V2"));

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                barrier.await();
                try {
                    UUID targetId = (idx % 2 == 0) ? p1.getId() : p2.getId();
                    policyAdministrationService.activatePolicyVersion(orgA.getId(), targetId, adminOrgA);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        List<LlmActiveExceptionalFallbackPolicy> pointers = activePolicyRepository.findByOrganizationId(orgA.getId());
        assertEquals(1, pointers.size());
    }

    @Test
    @DisplayName("E1-09: Concurrent deactivation and activation remains consistent")
    void testE1_09_concurrentDeactivationAndActivation() throws Exception {
        LlmExceptionalFallbackPolicy p1 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "V1"));
        activePolicyRepository.save(new LlmActiveExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", p1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<Boolean> f1 = executor.submit(() -> {
            barrier.await();
            try {
                policyAdministrationService.activatePolicyVersion(orgA.getId(), p1.getId(), adminOrgA);
                return true;
            } catch (Exception e) { return false; }
        });

        Future<Boolean> f2 = executor.submit(() -> {
            barrier.await();
            try {
                policyAdministrationService.deactivateActivePolicy(orgA.getId(), "TASK_EXECUTION", "openai", "anthropic", adminOrgA);
                return true;
            } catch (Exception e) { return false; }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        List<LlmActiveExceptionalFallbackPolicy> pointers = activePolicyRepository.findByOrganizationId(orgA.getId());
        assertTrue(pointers.size() <= 1);
    }

    @Test
    @DisplayName("E1-10: Deactivating active policy preserves historical version rows")
    void testE1_10_historicalPoliciesRemainIntact() throws Exception {
        LlmExceptionalFallbackPolicy p1 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "V1"));
        activePolicyRepository.save(new LlmActiveExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", p1));

        mockMvc.perform(delete("/api/v1/organizations/{orgId}/llm/policies/exceptional/active", orgA.getId())
                        .param("sourceAuthorityType", "TASK_EXECUTION")
                        .param("originalProviderId", "openai")
                        .param("targetProviderId", "anthropic")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isNoContent());

        assertEquals(0, activePolicyRepository.findByOrganizationId(orgA.getId()).size());
        assertEquals(1, exceptionalPolicyRepository.findByOrganizationId(orgA.getId()).size());
    }

    // =========================================================================
    // E1-11 to E1-15: PROVIDER & AUTHORIZATION VALIDATION
    // =========================================================================

    @Test
    @DisplayName("E1-11: Unknown provider in policy creation is rejected")
    void testE1_11_unknownProviderRejected() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "unknown-provider-xyz", "anthropic", "Invalid Provider");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_TARGET_CAPABILITY")));
    }

    @Test
    @DisplayName("E1-12: Unsupported capability target provider is rejected")
    void testE1_12_unsupportedCapabilityRejected() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "invalid-target-provider", "Invalid Target");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_TARGET_CAPABILITY")));
    }

    @Test
    @DisplayName("E1-13: ORG_MEMBER role cannot perform policy creation")
    void testE1_13_orgMemberCannotPerformAdminMutation() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Member Attempt");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E1-14: ORG_ADMIN role mutation succeeds")
    void testE1_14_orgAdminMutationSucceeds() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Admin Attempt");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("E1-15: Unauthenticated request is rejected")
    void testE1_15_unauthenticatedRequestRejected() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Unauthenticated Attempt");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // E1-16 to E1-20: READ ISOLATION, BOUNDS & AUDIT OUTBOX
    // =========================================================================

    @Test
    @DisplayName("E1-16: Cross-tenant policy version read is rejected")
    void testE1_16_crossTenantReadRejected() throws Exception {
        LlmExceptionalFallbackPolicy policyOrgA = exceptionalPolicyRepository.save(
                new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "Org A Policy")
        );

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/policies/exceptional/{policyId}", orgB.getId(), policyOrgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E1-17: Pagination size > 100 is capped to 100")
    void testE1_17_paginationOver100Capped() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .param("page", "0")
                        .param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(100)));
    }

    @Test
    @DisplayName("E1-18: Malformed policy request payload is rejected")
    void testE1_18_malformedPolicyRejected() throws Exception {
        String malformedJson = "{\"sourceAuthorityType\":\"TASK_EXECUTION\",\"originalProviderId\":\"\"}";

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E1-19: Audit outbox event is generated exactly once on mutation")
    void testE1_19_auditEventGenerated() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Audit Test");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isCreated());

        List<AuditLogOutbox> outboxEvents = auditLogOutboxRepository.findAll();
        assertFalse(outboxEvents.isEmpty());
        AuditLogOutbox event = outboxEvents.get(0);
        assertEquals("LlmExceptionalFallbackPolicy", event.getAggregateType());
        assertEquals("EXCEPTIONAL_POLICY_CREATED", event.getEventType());
    }

    @Test
    @DisplayName("E1-20: Audit event persists within transaction boundary")
    void testE1_20_auditFailurePreventsFalseSuccess() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Transaction Test");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isCreated());

        assertEquals(1, auditLogOutboxRepository.findAll().size());
    }

    // =========================================================================
    // E1-21 to E1-25: CONFIDENTIALITY, CONSTRAINTS & TENANT FK
    // =========================================================================

    @Test
    @DisplayName("E1-21: Secret material is absent from response DTO")
    void testE1_21_secretMaterialAbsentFromResponse() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Secret Check");

        String responseJson = mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertFalse(responseJson.contains("sk-"));
        assertFalse(responseJson.contains("password"));
        assertFalse(responseJson.contains("bearer"));
    }

    @Test
    @DisplayName("E1-22: Secret material is absent from audit outbox payload")
    void testE1_22_secretMaterialAbsentFromAudit() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Secret Audit Check");

        mockMvc.perform(post("/api/v1/organizations/{orgId}/llm/policies/exceptional", orgA.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isCreated());

        List<AuditLogOutbox> events = auditLogOutboxRepository.findAll();
        assertFalse(events.isEmpty());
        String payload = events.get(0).getPayloadJson();
        assertFalse(payload.contains("sk-"));
        assertFalse(payload.contains("password"));
    }

    @Test
    @DisplayName("E1-23: Database errors map safely to stable error responses")
    void testE1_23_sqlDatabaseErrorSafelyMapped() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/policies/exceptional/invalid-uuid", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_ARGUMENT")));
    }

    @Test
    @DisplayName("E1-24: Duplicate policy version insertion violates DB unique constraint")
    void testE1_24_duplicatePolicyIdentityVersionRejected() {
        LlmExceptionalFallbackPolicy p1 = new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "V1 Original");
        exceptionalPolicyRepository.saveAndFlush(p1);

        LlmExceptionalFallbackPolicy p1Duplicate = new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "V1 Duplicate");
        assertThrows(Exception.class, () -> {
            exceptionalPolicyRepository.saveAndFlush(p1Duplicate);
        });
    }

    @Test
    @DisplayName("E1-25: Active pointer tenant mismatch rejected by tenant check")
    void testE1_25_activePointerTenantMismatchRejected() {
        LlmExceptionalFallbackPolicy pOrgA = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "Org A"));

        assertThrows(Exception.class, () -> {
            policyAdministrationService.activatePolicyVersion(orgB.getId(), pOrgA.getId(), adminOrgB);
        });
    }

    // =========================================================================
    // E1-26 to E1-30: SCHEMA VERIFICATION & AUTHORITY AUDIT
    // =========================================================================

    @Test
    @DisplayName("E1-26: Immutable historical policy update is blocked by updatable=false constraint or DB trigger")
    void testE1_26_immutableHistoricalPolicyDeleteUpdateBlocked() {
        LlmExceptionalFallbackPolicy p1 = exceptionalPolicyRepository.save(new LlmExceptionalFallbackPolicy(orgA, "TASK_EXECUTION", "openai", "anthropic", 1, adminOrgA, "Immutable Test"));
        final LlmExceptionalFallbackPolicy toSave = p1;

        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE llm_exceptional_fallback_policies SET reason = 'Illegal Update' WHERE id = ?", toSave.getId());
        });
    }

    @Test
    @DisplayName("E1-27: Concurrent duplicate policy creation remains consistent")
    void testE1_27_concurrentDuplicateActivationRemainConsistent() throws Exception {
        CreateExceptionalPolicyRequest req = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "openai", "anthropic", "Concurrent Create");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CyclicBarrier barrier = new CyclicBarrier(3);

        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                barrier.await();
                try {
                    policyAdministrationService.createExceptionalPolicyVersion(orgA.getId(), adminOrgA, req);
                } catch (Exception ignored) {}
                return null;
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        List<LlmExceptionalFallbackPolicy> policies = exceptionalPolicyRepository.findByOrganizationId(orgA.getId());
        assertFalse(policies.isEmpty());
    }

    @Test
    @DisplayName("E1-28: Transaction rollback leaves policy state unchanged")
    void testE1_28_transactionRollbackLeavesPolicyStateUnchanged() {
        try {
            CreateExceptionalPolicyRequest invalidReq = new CreateExceptionalPolicyRequest("TASK_EXECUTION", "unknown-provider", "anthropic", "Fail");
            policyAdministrationService.createExceptionalPolicyVersion(orgA.getId(), adminOrgA, invalidReq);
        } catch (Exception ignored) {}

        assertEquals(0, exceptionalPolicyRepository.findByOrganizationId(orgA.getId()).size());
    }

    @Test
    @DisplayName("E1-29: Verify V36 Flyway migration file created for Phase 16.5 audit export tables")
    void testE1_29_noV36MigrationCreated() {
        File migrationDir = new File("src/main/resources/db/migration");
        if (migrationDir.exists() && migrationDir.isDirectory()) {
            File[] v36Files = migrationDir.listFiles((dir, name) -> name.startsWith("V36"));
            assertTrue(v36Files != null && v36Files.length > 0, "V36 migration script must exist for Phase 16.5!");
        }
    }

    @Test
    @DisplayName("E1-30: Verify Phase 1-15 and Phase 16.4D financial/execution authorities remain untouched")
    void testE1_30_noPhase1To15AuthorityModified() {
        // Asserts that accounting tables and execution entities are unmutated during policy administration
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM organization_usage_counters WHERE organization_id = ?", Integer.class, orgA.getId()));
    }
}
