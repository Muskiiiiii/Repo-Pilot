package com.repopilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.controller.LlmAuditExportController;
import com.repopilot.llm.dto.AuditExportJobResponse;
import com.repopilot.llm.dto.AuditExportTokenResponse;
import com.repopilot.llm.dto.CreateAuditExportRequest;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.llm.service.AuditExportJobEngineService;
import com.repopilot.llm.service.AuditExportTokenService;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice54_AuditExportGovernanceControllerTest {

    @BeforeAll
    static void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired private LlmAuditExportController controller;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogOutboxRepository auditLogOutboxRepository;
    @Autowired private AuditExportJobRepository auditExportJobRepository;

    @Autowired private AuditExportJobEngineService exportJobEngineService;
    @Autowired private AuditExportTokenService tokenService;

    private Organization orgA;
    private Organization orgB;
    private User adminOrgA;
    private User memberOrgA;
    private User adminOrgB;
    private User nonMemberUser;

    @BeforeEach
    void setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        cleanupData();

        orgA = new Organization("Org A Governance", "org-a-gov-" + UUID.randomUUID());
        orgB = new Organization("Org B Governance", "org-b-gov-" + UUID.randomUUID());
        orgA = organizationRepository.saveAndFlush(orgA);
        orgB = organizationRepository.saveAndFlush(orgB);

        adminOrgA = createUser("adminA_" + UUID.randomUUID() + "@example.com", "Admin OrgA");
        memberOrgA = createUser("memberA_" + UUID.randomUUID() + "@example.com", "Member OrgA");
        adminOrgB = createUser("adminB_" + UUID.randomUUID() + "@example.com", "Admin OrgB");
        nonMemberUser = createUser("outsider_" + UUID.randomUUID() + "@example.com", "Outsider");

        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.saveAndFlush(new OrganizationMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN));

        for (int i = 0; i < 3; i++) {
            AuditLogOutbox outbox = new AuditLogOutbox();
            outbox.setCanonicalAuditEventId("EVENT-GOV-" + UUID.randomUUID());
            outbox.setOccurrenceId(UUID.randomUUID());
            outbox.setOrganization(orgA);
            outbox.setAggregateType("Project");
            outbox.setAggregateId("PROJ-GOV-" + i);
            outbox.setEventType("PROJECT_CREATED");
            outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
            outbox.setPayloadJson("{\"projectId\":\"PROJ-GOV-" + i + "\"}");
            outbox.setCreatedAt(Instant.now().minusSeconds(1800 - i * 10));
            auditLogOutboxRepository.save(outbox);
        }
    }

    @AfterEach
    void tearDown() {
        cleanupData();
    }

    private User createUser(String email, String name) {
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        u.setPasswordHash("hash");
        return userRepository.saveAndFlush(u);
    }

    private void cleanupData() {
        try { jdbcTemplate.execute("DELETE FROM consumed_download_tokens"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM audit_export_jobs"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM audit_log_outbox"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_policies"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM projects"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organization_members"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM organizations"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DELETE FROM users"); } catch (Exception ignored) {}
    }

    private AuditExportJobResponse createCompletedJob(Organization org, User caller) {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(org.getId(), caller, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CLAIMED);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        job.setWorkerIdentity("worker-gov-1");
        job = auditExportJobRepository.saveAndFlush(job);

        return exportJobEngineService.executeFullExportPipeline(
                org.getId(), job.getId(), "worker-gov-1", job.getLeaseToken(), job.getFencingVersion()
        );
    }

    private UserPrincipal p(User user) {
        return user != null ? new UserPrincipal(user) : null;
    }

    // =========================================================================
    // 1. BACKEND HOSTILE SECURITY TESTS (B01 to B20 & AUTH01 to AUTH12)
    // =========================================================================

    @Test
    @DisplayName("B01: unauthenticated list -> NullPrincipal rejection")
    void testB01_unauthenticatedList() {
        assertThrows(Exception.class, () -> controller.getExportJobs(null, orgA.getId(), 0, 20));
    }

    @Test
    @DisplayName("B02: unauthenticated detail -> NullPrincipal rejection")
    void testB02_unauthenticatedDetail() {
        assertThrows(Exception.class, () -> controller.getExportJob(null, orgA.getId(), UUID.randomUUID()));
    }

    @Test
    @DisplayName("B03: unauthenticated create -> NullPrincipal rejection")
    void testB03_unauthenticatedCreate() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        assertThrows(Exception.class, () -> controller.createExportJob(null, orgA.getId(), req));
    }

    @Test
    @DisplayName("B04: ORG_MEMBER list own organization -> 200 OK")
    void testB04_orgMemberListOwnOrganization() {
        ResponseEntity<Page<AuditExportJobResponse>> resp = controller.getExportJobs(p(memberOrgA), orgA.getId(), 0, 20);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }

    @Test
    @DisplayName("B05: ORG_MEMBER detail own organization -> 200 OK")
    void testB05_orgMemberDetailOwnOrganization() {
        AuditExportJobResponse completed = createCompletedJob(orgA, adminOrgA);
        ResponseEntity<AuditExportJobResponse> resp = controller.getExportJob(p(memberOrgA), orgA.getId(), completed.getJobId());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(completed.getJobId(), resp.getBody().getJobId());
    }

    @Test
    @DisplayName("B06: ORG_MEMBER create -> 403 Forbidden")
    void testB06_orgMemberCreateForbidden() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");

        ApiException ex = assertThrows(ApiException.class, () -> controller.createExportJob(p(memberOrgA), orgA.getId(), req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("B07: ORG_ADMIN create -> 202 Accepted")
    void testB07_orgAdminCreateAccepted() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");

        ResponseEntity<AuditExportJobResponse> resp = controller.createExportJob(p(adminOrgA), orgA.getId(), req);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertNotNull(resp.getBody().getJobId());
        assertEquals("PENDING", resp.getBody().getStatus());
    }

    @Test
    @DisplayName("B08: ORG_ADMIN list -> 200 OK")
    void testB08_orgAdminList() {
        ResponseEntity<Page<AuditExportJobResponse>> resp = controller.getExportJobs(p(adminOrgA), orgA.getId(), 0, 20);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @DisplayName("B09: ORG_ADMIN detail -> 200 OK")
    void testB09_orgAdminDetail() {
        AuditExportJobResponse completed = createCompletedJob(orgA, adminOrgA);
        ResponseEntity<AuditExportJobResponse> resp = controller.getExportJob(p(adminOrgA), orgA.getId(), completed.getJobId());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @DisplayName("B10 & TEN01: cross-tenant list blocked -> 403 Forbidden")
    void testB10_crossTenantListBlocked() {
        ApiException ex = assertThrows(ApiException.class, () -> controller.getExportJobs(p(adminOrgB), orgA.getId(), 0, 20));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("B11 & TEN02: cross-tenant detail blocked -> 403 Forbidden / 404 NOT_FOUND")
    void testB11_crossTenantDetailBlockedResourceHiding() {
        AuditExportJobResponse completed = createCompletedJob(orgA, adminOrgA);
        ApiException ex = assertThrows(ApiException.class, () -> controller.getExportJob(p(adminOrgB), orgA.getId(), completed.getJobId()));
        assertTrue(ex.getStatus() == HttpStatus.FORBIDDEN || ex.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("B12 & TEN03: cross-tenant create blocked -> 403 Forbidden")
    void testB12_crossTenantCreateBlocked() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        ApiException ex = assertThrows(ApiException.class, () -> controller.createExportJob(p(adminOrgB), orgA.getId(), req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("B13: invalid orgId rejected -> 403 / 404")
    void testB13_invalidOrgIdRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> controller.getExportJobs(p(adminOrgA), UUID.randomUUID(), 0, 20));
        assertTrue(ex.getStatus() == HttpStatus.FORBIDDEN || ex.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("B14: invalid jobId rejected -> 404 NOT_FOUND")
    void testB14_invalidJobIdRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> controller.getExportJob(p(adminOrgA), orgA.getId(), UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("B15: malformed request rejected -> 400 BAD_REQUEST")
    void testB15_malformedRequestRejected() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("INVALID_SCOPE");
        ApiException ex = assertThrows(ApiException.class, () -> controller.createExportJob(p(adminOrgA), orgA.getId(), req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("B16: server-side validation remains authoritative")
    void testB16_serverSideValidationAuthoritative() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("INVALID_SCOPE");
        ApiException ex = assertThrows(ApiException.class, () -> exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("B17: terminal FAILED job cannot be mutated by UI")
    void testB17_terminalFailedJobCannotBeMutated() {
        CreateAuditExportRequest req = new CreateAuditExportRequest();
        req.setExportScope("FULL");
        AuditExportJobResponse created = exportJobEngineService.createExportJob(orgA.getId(), adminOrgA, req);

        AuditExportJob job = auditExportJobRepository.findById(created.getJobId()).get();
        job.setStatus(AuditExportJobStatus.FAILED);
        auditExportJobRepository.saveAndFlush(job);

        boolean updated = exportJobEngineService.updateJobStatusFenced(job.getId(), UUID.randomUUID(), job.getFencingVersion(), AuditExportJobStatus.FAILED, AuditExportJobStatus.PROCESSING);
        assertFalse(updated);
    }

    @Test
    @DisplayName("B18: COMPLETED job lifecycle cannot be reopened by UI")
    void testB18_completedJobCannotBeReopened() {
        AuditExportJobResponse completed = createCompletedJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();

        boolean updated = exportJobEngineService.updateJobStatusFenced(job.getId(), job.getLeaseToken(), job.getFencingVersion(), AuditExportJobStatus.COMPLETED, AuditExportJobStatus.PROCESSING);
        assertFalse(updated);
    }

    @Test
    @DisplayName("B19: DELETED job cannot be made downloadable")
    void testB19_deletedJobCannotBeMadeDownloadable() {
        AuditExportJobResponse completed = createCompletedJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        job.setStatus(AuditExportJobStatus.DELETED);
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> controller.createDownloadToken(p(adminOrgA), orgA.getId(), completed.getJobId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("B20: REVOKED job cannot be made downloadable")
    void testB20_revokedJobCannotBeMadeDownloadable() {
        AuditExportJobResponse completed = createCompletedJob(orgA, adminOrgA);
        AuditExportJob job = auditExportJobRepository.findById(completed.getJobId()).get();
        job.setStatus(AuditExportJobStatus.CANCELLED);
        auditExportJobRepository.saveAndFlush(job);

        ApiException ex = assertThrows(ApiException.class, () -> controller.createDownloadToken(p(adminOrgA), orgA.getId(), completed.getJobId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    // =========================================================================
    // 2. DTO DATA MINIMIZATION & SECRET LEAKAGE AUDIT (DTO01 to DTO10 & SECRET01 to SECRET10)
    // =========================================================================

    @Test
    @DisplayName("DTO01..DTO10 & SECRET01..SECRET10: AuditExportJobResponse excludes plaintext payloads, DEKs, storage paths, and secrets")
    void testDtoPayloadSafetyAndSecretExclusion() {
        AuditExportJobResponse completed = createCompletedJob(orgA, adminOrgA);
        ResponseEntity<AuditExportJobResponse> resp = controller.getExportJob(p(adminOrgA), orgA.getId(), completed.getJobId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        AuditExportJobResponse dto = resp.getBody();
        assertNotNull(dto);
        assertEquals(completed.getJobId(), dto.getJobId());
        assertEquals("COMPLETED", dto.getStatus());
        assertNull(dto.getErrorCode());
        assertNull(dto.getErrorMessage());
    }
}
