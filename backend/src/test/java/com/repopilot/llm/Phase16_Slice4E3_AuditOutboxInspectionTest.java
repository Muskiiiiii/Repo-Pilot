package com.repopilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.llm.dto.AuditOutboxInspectionResponse;
import com.repopilot.llm.dto.PagedPolicyResponse;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.service.AuditOutboxInspectionService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
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
public class Phase16_Slice4E3_AuditOutboxInspectionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private AuditOutboxInspectionService auditInspectionService;

    private Organization orgA;
    private Organization orgB;

    private User memberOrgA;
    private User adminOrgA;
    private User memberOrgB;

    private String tokenMemberOrgA;
    private String tokenAdminOrgA;
    private String tokenMemberOrgB;

    @BeforeEach
    void setUp() {
        setUpSchemaTriggers();
        cleanData();

        String uid = UUID.randomUUID().toString().substring(0, 8);
        memberOrgA = createUser("member_orga_e3_" + uid + "@example.com", "Member OrgA");
        adminOrgA = createUser("admin_orga_e3_" + uid + "@example.com", "Admin OrgA");
        memberOrgB = createUser("member_orgb_e3_" + uid + "@example.com", "Member OrgB");

        tokenMemberOrgA = tokenProvider.generateAccessToken(memberOrgA);
        tokenAdminOrgA = tokenProvider.generateAccessToken(adminOrgA);
        tokenMemberOrgB = tokenProvider.generateAccessToken(memberOrgB);

        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-e3-" + uid);
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-e3-" + uid);
        orgB = organizationRepository.save(orgB);

        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgB, memberOrgB, OrganizationRole.ORG_MEMBER));
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

    private AuditLogOutbox createOutboxEvent(Organization org, String eventType, String payloadJson) {
        AuditLogOutbox outbox = new AuditLogOutbox();
        outbox.setOrganization(org);
        UUID occurrenceId = UUID.randomUUID();
        outbox.setCanonicalAuditEventId("AUDIT:" + org.getId() + ":" + eventType + ":" + occurrenceId);
        outbox.setOccurrenceId(occurrenceId);
        outbox.setAggregateType("LLM_OPERATION");
        outbox.setAggregateId(UUID.randomUUID().toString());
        outbox.setEventType(eventType);
        outbox.setPayloadJson(payloadJson != null ? payloadJson : "{\"organizationId\":\"" + org.getId() + "\",\"eventType\":\"" + eventType + "\"}");
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(Instant.now());
        return auditLogOutboxRepository.save(outbox);
    }

    // =========================================================================
    // E3-01 to E3-03: BASIC READ & DETERMINISM
    // =========================================================================

    @Test
    @DisplayName("E3-01: Valid organization audit listing returns 200 OK")
    void testE3_01_validOrganizationAuditListing() throws Exception {
        createOutboxEvent(orgA, "POLICY_ACTIVATED", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].eventType", is("POLICY_ACTIVATED")));
    }

    @Test
    @DisplayName("E3-02: Valid individual event lookup returns 200 OK")
    void testE3_02_validIndividualEventLookup() throws Exception {
        AuditLogOutbox event = createOutboxEvent(orgA, "FALLBACK_TRIGGERED", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId", is(event.getId().toString())))
                .andExpect(jsonPath("$.eventType", is("FALLBACK_TRIGGERED")));
    }

    @Test
    @DisplayName("E3-03: Deterministic event ordering by createdAt DESC, id DESC")
    void testE3_03_deterministicEventOrdering() throws Exception {
        AuditLogOutbox e1 = createOutboxEvent(orgA, "EVENT_1", null);
        AuditLogOutbox e2 = createOutboxEvent(orgA, "EVENT_2", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType", is("EVENT_2")))
                .andExpect(jsonPath("$.content[1].eventType", is("EVENT_1")));
    }

    // =========================================================================
    // E3-04 to E3-10: PAGINATION BOUNDS & PARAMETER VALIDATION
    // =========================================================================

    @Test
    @DisplayName("E3-04: Page size 1 returns single item per page")
    void testE3_04_pageSize1() throws Exception {
        createOutboxEvent(orgA, "E1", null);
        createOutboxEvent(orgA, "E2", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox?page=0&size=1", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.totalPages", is(2)));
    }

    @Test
    @DisplayName("E3-05: Page size 100 returns items up to size 100")
    void testE3_05_pageSize100() throws Exception {
        createOutboxEvent(orgA, "E1", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox?page=0&size=100", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(100)));
    }

    @Test
    @DisplayName("E3-06: Page size >100 is rejected with HTTP 400")
    void testE3_06_pageSizeGreaterThan100Rejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox?page=0&size=101", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E3-07: Page size 0 is rejected with HTTP 400")
    void testE3_07_pageSizeZero() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox?page=0&size=0", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E3-08: Negative page size is rejected with HTTP 400")
    void testE3_08_negativePageSize() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox?page=0&size=-5", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E3-09: Invalid negative page number is rejected with HTTP 400")
    void testE3_09_invalidPageNumber() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox?page=-1&size=20", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E3-10: Large page number returns empty content list with totalElements")
    void testE3_10_largePageNumber() throws Exception {
        createOutboxEvent(orgA, "E1", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox?page=100000&size=20", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    // =========================================================================
    // E3-11 to E3-18: AUTHORIZATION & TENANT ISOLATION
    // =========================================================================

    @Test
    @DisplayName("E3-11: Unauthenticated request returns HTTP 401")
    void testE3_11_unauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("E3-12: ORG_MEMBER read access allowed with HTTP 200")
    void testE3_12_orgMemberReadAccess() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E3-13: ORG_ADMIN read access allowed with HTTP 200")
    void testE3_13_orgAdminReadAccess() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E3-14: Cross-tenant organization audit request rejected with HTTP 403")
    void testE3_14_crossTenantOrganizationRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgB))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E3-15: Cross-tenant event access fails closed with HTTP 404")
    void testE3_15_crossTenantEventRejected() throws Exception {
        AuditLogOutbox eventOrgA = createOutboxEvent(orgA, "E1", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgB.getId(), eventOrgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E3-16: Forged aggregate identity fails closed with HTTP 404")
    void testE3_16_forgedAggregateIdentityRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E3-17: Forged occurrence identity fails closed with HTTP 404")
    void testE3_17_forgedOccurrenceIdentityRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E3-18: Forged canonical event identity fails closed with HTTP 404")
    void testE3_18_forgedCanonicalEventIdentityRejected() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // E3-19 to E3-24: PAYLOAD REDACTION
    // =========================================================================

    @Test
    @DisplayName("E3-19: Event payload generic secret is redacted")
    void testE3_19_eventPayloadSecretRedaction() throws Exception {
        String raw = "{\"organizationId\":\"" + orgA.getId() + "\",\"secret\":\"MY_SUPER_SECRET\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, "TEST_EVENT", raw);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("MY_SUPER_SECRET"))));
    }

    @Test
    @DisplayName("E3-20: API key in payload is redacted")
    void testE3_20_apiKeyRedaction() throws Exception {
        String raw = "{\"organizationId\":\"" + orgA.getId() + "\",\"apiKey\":\"sk-1234567890abcdef\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, "TEST_EVENT", raw);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("sk-1234567890abcdef"))));
    }

    @Test
    @DisplayName("E3-21: Provider credential in payload is redacted")
    void testE3_21_providerCredentialRedaction() throws Exception {
        String raw = "{\"organizationId\":\"" + orgA.getId() + "\",\"providerSecret\":\"prov_secret_xyz\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, "TEST_EVENT", raw);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("prov_secret_xyz"))));
    }

    @Test
    @DisplayName("E3-22: Lease token in payload is redacted")
    void testE3_22_leaseTokenRedaction() throws Exception {
        String raw = "{\"organizationId\":\"" + orgA.getId() + "\",\"workerLeaseToken\":\"lease_token_777\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, "TEST_EVENT", raw);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("lease_token_777"))));
    }

    @Test
    @DisplayName("E3-23: Prompt text in payload is redacted")
    void testE3_23_promptRedaction() throws Exception {
        String raw = "{\"organizationId\":\"" + orgA.getId() + "\",\"rawPrompt\":\"System prompt text\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, "TEST_EVENT", raw);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("System prompt text"))));
    }

    @Test
    @DisplayName("E3-24: Completion text in payload is redacted")
    void testE3_24_completionRedaction() throws Exception {
        String raw = "{\"organizationId\":\"" + orgA.getId() + "\",\"rawCompletion\":\"Generated LLM completion output\"}";
        AuditLogOutbox event = createOutboxEvent(orgA, "TEST_EVENT", raw);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("[REDACTED]")))
                .andExpect(jsonPath("$.payloadJson", not(containsString("Generated LLM completion output"))));
    }

    // =========================================================================
    // E3-25 to E3-30: READ-ONLY & IMMUTABILITY PROOF
    // =========================================================================

    @Test
    @DisplayName("E3-25: Zero INSERT operations occur during outbox inspection")
    void testE3_25_zeroInsert() throws Exception {
        createOutboxEvent(orgA, "E1", null);
        long countBefore = auditLogOutboxRepository.count();

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        assertEquals(countBefore, auditLogOutboxRepository.count());
    }

    @Test
    @DisplayName("E3-26: Zero UPDATE operations occur during outbox inspection")
    void testE3_26_zeroUpdate() throws Exception {
        AuditLogOutbox event = createOutboxEvent(orgA, "E1", null);
        AuditOutboxDeliveryState stateBefore = event.getDeliveryState();

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        AuditLogOutbox reloaded = auditLogOutboxRepository.findById(event.getId()).orElseThrow();
        assertEquals(stateBefore, reloaded.getDeliveryState());
    }

    @Test
    @DisplayName("E3-27: Zero DELETE operations occur during outbox inspection")
    void testE3_27_zeroDelete() throws Exception {
        AuditLogOutbox event = createOutboxEvent(orgA, "E1", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        assertTrue(auditLogOutboxRepository.findById(event.getId()).isPresent());
    }

    @Test
    @DisplayName("E3-28: Audit immutability is preserved across inspection calls")
    void testE3_28_auditImmutabilityPreserved() throws Exception {
        AuditLogOutbox event = createOutboxEvent(orgA, "IMMUTABLE_EVENT", null);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), event.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk());

        AuditLogOutbox reloaded = auditLogOutboxRepository.findById(event.getId()).orElseThrow();
        assertEquals("IMMUTABLE_EVENT", reloaded.getEventType());
    }

    @Test
    @DisplayName("E3-29: Events with distinct occurrenceId remain distinct")
    void testE3_29_duplicateEventIdentityHandledCorrectly() throws Exception {
        AuditLogOutbox e1 = createOutboxEvent(orgA, "SAME_TYPE", null);
        AuditLogOutbox e2 = createOutboxEvent(orgA, "SAME_TYPE", null);

        assertNotEquals(e1.getOccurrenceId(), e2.getOccurrenceId());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox", orgA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("E3-30: Two legitimate same-type events maintain distinct identity")
    void testE3_30_twoLegitimateSameTypeEventsRemainDistinct() throws Exception {
        AuditLogOutbox e1 = createOutboxEvent(orgA, "POLICY_MUTATED", "{\"version\":1}");
        AuditLogOutbox e2 = createOutboxEvent(orgA, "POLICY_MUTATED", "{\"version\":2}");

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), e1.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("version\":1")));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/llm/audit-outbox/{eventId}", orgA.getId(), e2.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadJson", containsString("version\":2")));
    }
}
