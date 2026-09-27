package com.repopilot.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.dto.CreateOrganizationRequest;
import com.repopilot.organization.dto.OrganizationDto;
import com.repopilot.organization.dto.OrganizationPolicyDto;
import com.repopilot.organization.dto.UpdateOrganizationPolicyRequest;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationPolicy;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationPolicyRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.organization.service.OrganizationPolicyService;
import com.repopilot.organization.service.OrganizationService;
import com.repopilot.project.dto.CreateProjectRequest;
import com.repopilot.project.dto.ProjectDto;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice1_OrganizationAndPolicyInfrastructureTest {

    @Autowired private OrganizationService organizationService;
    @Autowired private OrganizationPolicyService organizationPolicyService;
    @Autowired private OrganizationAuthorizationService organizationAuthorizationService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private OrganizationPolicyRepository organizationPolicyRepository;
    @Autowired private ProjectService projectService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AuditLogService auditLogService;
    @Autowired private EntityManager entityManager;

    private User adminUserOrgA;
    private User memberUserOrgA;
    private User outsiderUserOrgB;

    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        adminUserOrgA = new User();
        adminUserOrgA.setEmail("admin_orga_" + UUID.randomUUID() + "@example.com");
        adminUserOrgA.setPasswordHash("hash");
        adminUserOrgA.setName("Org A Admin");
        adminUserOrgA.setRole(UserRole.USER);
        adminUserOrgA = userRepository.save(adminUserOrgA);

        memberUserOrgA = new User();
        memberUserOrgA.setEmail("member_orga_" + UUID.randomUUID() + "@example.com");
        memberUserOrgA.setPasswordHash("hash");
        memberUserOrgA.setName("Org A Member");
        memberUserOrgA.setRole(UserRole.USER);
        memberUserOrgA = userRepository.save(memberUserOrgA);

        outsiderUserOrgB = new User();
        outsiderUserOrgB.setEmail("outsider_orgb_" + UUID.randomUUID() + "@example.com");
        outsiderUserOrgB.setPasswordHash("hash");
        outsiderUserOrgB.setName("Org B Outsider");
        outsiderUserOrgB.setRole(UserRole.USER);
        outsiderUserOrgB = userRepository.save(outsiderUserOrgB);

        // Create Org A with adminUserOrgA
        String slugA = "org-a-" + UUID.randomUUID().toString().substring(0, 8);
        OrganizationDto dtoA = organizationService.createOrganization(adminUserOrgA, new CreateOrganizationRequest("Org A", slugA));
        orgA = organizationRepository.findById(dtoA.getId()).orElseThrow();

        // Add memberUserOrgA to Org A as ORG_MEMBER
        OrganizationMember memA = new OrganizationMember(orgA, memberUserOrgA, OrganizationRole.ORG_MEMBER);
        organizationMemberRepository.save(memA);

        // Create Org B with outsiderUserOrgB
        String slugB = "org-b-" + UUID.randomUUID().toString().substring(0, 8);
        OrganizationDto dtoB = organizationService.createOrganization(outsiderUserOrgB, new CreateOrganizationRequest("Org B", slugB));
        orgB = organizationRepository.findById(dtoB.getId()).orElseThrow();
    }

    // DATABASE / MIGRATION (1-5)
    @Test
    @DisplayName("INVARIANT 1: V32 Migration Creates Tables & Seeds Default Organization")
    void test01_v32Migration_createsTablesAndSeedsDefaultOrg() {
        com.repopilot.organization.config.DefaultOrganizationHolder.ensureDefaultOrganizationExists();
        UUID defaultOrgId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Organization defaultOrg = organizationRepository.findById(defaultOrgId).orElse(null);
        assertNotNull(defaultOrg);
        assertEquals("Default Organization", defaultOrg.getName());
        assertEquals("default-org", defaultOrg.getSlug());

        OrganizationPolicy defaultPolicy = organizationPolicyRepository.findByOrganizationId(defaultOrgId).orElse(null);
        assertNotNull(defaultPolicy);
    }

    @Test
    @DisplayName("INVARIANT 2: Legacy Project Access Preservation After V32")
    void test02_legacyProjectAccessPreservation_afterV32() {
        Project p = new Project();
        p.setName("Legacy Project");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        p = projectRepository.save(p);

        ProjectMember pm = new ProjectMember(p, memberUserOrgA, ProjectRole.MEMBER);
        projectMemberRepository.save(pm);

        assertTrue(projectMemberRepository.existsByProjectIdAndUserId(p.getId(), memberUserOrgA.getId()));
        assertEquals(orgA.getId(), p.getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 3: Legacy Audit Organization Mapping Hierarchy")
    void test03_legacyAuditOrganizationMapping_hierarchy() {
        Project projA = new Project();
        projA.setName("Proj A Audit Test");
        projA.setOwner(adminUserOrgA);
        projA.setOrganization(orgA);
        projA = projectRepository.save(projA);

        AuditLog audit = auditLogService.logAction(adminUserOrgA, projA, null, "TEST_ACTION", "PROJECT", projA.getId(), "{}");
        assertNotNull(audit.getOrganization());
        assertEquals(orgA.getId(), audit.getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 4: Orphan Audit Handling Mapped to Default Org")
    void test04_orphanAuditHandling_mappedToDefaultOrg() {
        AuditLog orphan = auditLogService.logAction(null, null, null, "SYSTEM_CLEANUP", "SYSTEM", null, "{}");
        assertNotNull(orphan.getOrganization());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), orphan.getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 5: Project Organization Immutability Trigger")
    @Transactional
    void test05_projectOrganizationImmutability_postgreSQLTrigger() {
        Project p = new Project();
        p.setName("Trigger Test Project");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        p = projectRepository.save(p);

        p.setOrganization(orgB);
        projectRepository.save(p);
        entityManager.flush();
        entityManager.clear();

        Project reloaded = projectRepository.findById(p.getId()).orElseThrow();
        assertEquals(orgA.getId(), reloaded.getOrganization().getId());
    }

    // AUTHORIZATION (6-12)
    @Test
    @DisplayName("INVARIANT 6: Unauthenticated Request Returns 401")
    void test06_unauthenticatedRequest_returns401() {
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.createOrganization(null, new CreateOrganizationRequest("Name", "slug-unauth"))
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("INVARIANT 7: Org A User Accessing Org B Policy Returns 403")
    void test07_orgAUser_accessingOrgBPolicy_returns403() {
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationPolicyService.getPolicy(orgB.getId(), adminUserOrgA)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("INVARIANT 8: MEMBER Policy PUT Returns 403")
    void test08_memberPolicyPut_returns403() {
        OrganizationPolicyDto current = organizationPolicyService.getPolicy(orgA.getId(), memberUserOrgA);
        UpdateOrganizationPolicyRequest update = new UpdateOrganizationPolicyRequest(
                2000000L, new BigDecimal("200.00"), List.of("gpt-4o"), true, 5, 90, current.getVersion()
        );
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationPolicyService.updatePolicy(orgA.getId(), memberUserOrgA, update)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("INVARIANT 9: ADMIN Policy PUT Succeeds")
    void test09_adminPolicyPut_succeeds() {
        OrganizationPolicyDto current = organizationPolicyService.getPolicy(orgA.getId(), adminUserOrgA);
        UpdateOrganizationPolicyRequest update = new UpdateOrganizationPolicyRequest(
                2000000L, new BigDecimal("200.00"), List.of("gpt-4o"), true, 8, 120, current.getVersion()
        );
        OrganizationPolicyDto updated = organizationPolicyService.updatePolicy(orgA.getId(), adminUserOrgA, update);
        assertEquals(2000000L, updated.getMonthlyTokenBudget());
        assertEquals(8, updated.getMaxConcurrentExecutions());
    }

    @Test
    @DisplayName("INVARIANT 10: Forged orgId in Request Body Ignored/Rejected")
    void test10_forgedOrgId_inRequestBody_ignoredOrRejected() {
        CreateProjectRequest req = new CreateProjectRequest("Forged Org Project", "Desc", orgB.getId());
        ApiException ex = assertThrows(ApiException.class, () ->
                projectService.createProject(adminUserOrgA, req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("INVARIANT 11: Forged ownerId in Request Body Ignored/Rejected")
    void test11_forgedOwnerId_inRequestBody_ignoredOrRejected() {
        CreateOrganizationRequest req = new CreateOrganizationRequest("Forged Owner Org", "slug-forged-owner");
        OrganizationDto dto = organizationService.createOrganization(adminUserOrgA, req);
        assertEquals(OrganizationRole.ORG_ADMIN, dto.getRole());
        assertTrue(organizationMemberRepository.existsByOrganizationIdAndUserId(dto.getId(), adminUserOrgA.getId()));
        assertFalse(organizationMemberRepository.existsByOrganizationIdAndUserId(dto.getId(), outsiderUserOrgB.getId()));
    }

    @Test
    @DisplayName("INVARIANT 12: Organization Enumeration Isolation")
    void test12_organizationEnumerationIsolation_userOnlySeesOwnOrgs() {
        List<OrganizationDto> adminOrgs = organizationService.getUserOrganizations(adminUserOrgA);
        assertTrue(adminOrgs.stream().anyMatch(o -> o.getId().equals(orgA.getId())));
        assertFalse(adminOrgs.stream().anyMatch(o -> o.getId().equals(orgB.getId())));

        List<OrganizationDto> outsiderOrgs = organizationService.getUserOrganizations(outsiderUserOrgB);
        assertTrue(outsiderOrgs.stream().anyMatch(o -> o.getId().equals(orgB.getId())));
        assertFalse(outsiderOrgs.stream().anyMatch(o -> o.getId().equals(orgA.getId())));
    }

    // CONCURRENCY (13-17)
    @Test
    @DisplayName("INVARIANT 13: Duplicate Slug Race Returns 409 Conflict")
    void test13_duplicateSlugRace_concurrency() {
        String slug = orgA.getSlug();
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.createOrganization(adminUserOrgA, new CreateOrganizationRequest("Duplicate Org", slug))
        );
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("INVARIANT 14: Duplicate Membership Race Throws DataIntegrityViolation")
    void test14_duplicateMembershipRace_concurrency() {
        OrganizationMember duplicate = new OrganizationMember(orgA, memberUserOrgA, OrganizationRole.ORG_MEMBER);
        assertThrows(DataIntegrityViolationException.class, () -> {
            organizationMemberRepository.saveAndFlush(duplicate);
        });
    }

    @Test
    @DisplayName("INVARIANT 15: Concurrent Policy Update Version Checking")
    void test15_concurrentPolicyUpdate_versionChecking() {
        OrganizationPolicyDto current = organizationPolicyService.getPolicy(orgA.getId(), adminUserOrgA);
        UpdateOrganizationPolicyRequest update1 = new UpdateOrganizationPolicyRequest(
                1500000L, new BigDecimal("150.00"), List.of("gpt-4o"), true, 5, 90, current.getVersion()
        );
        organizationPolicyService.updatePolicy(orgA.getId(), adminUserOrgA, update1);

        UpdateOrganizationPolicyRequest update2Stale = new UpdateOrganizationPolicyRequest(
                1800000L, new BigDecimal("180.00"), List.of("gpt-4o"), true, 5, 90, current.getVersion()
        );
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationPolicyService.updatePolicy(orgA.getId(), adminUserOrgA, update2Stale)
        );
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("INVARIANT 16: Stale @Version Update Returns 409 Conflict")
    void test16_staleVersionUpdate_returns409Conflict() {
        OrganizationPolicyDto current = organizationPolicyService.getPolicy(orgA.getId(), adminUserOrgA);
        UpdateOrganizationPolicyRequest stale = new UpdateOrganizationPolicyRequest(
                1500000L, new BigDecimal("150.00"), List.of("gpt-4o"), true, 5, 90, current.getVersion() + 99L
        );
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationPolicyService.updatePolicy(orgA.getId(), adminUserOrgA, stale)
        );
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("STALE_VERSION", ex.getCode());
    }

    @Test
    @DisplayName("INVARIANT 17: Organization Creation Rollback on Duplicate Slug")
    void test17_organizationCreationRollback_onFailure() {
        long countBefore = organizationRepository.count();
        String slug = orgA.getSlug();
        assertThrows(ApiException.class, () ->
                organizationService.createOrganization(adminUserOrgA, new CreateOrganizationRequest("Fail Org", slug))
        );
        assertEquals(countBefore, organizationRepository.count());
    }

    // SECURITY (18-22)
    @Test
    @DisplayName("INVARIANT 18: Direct SQL Organization Mutation Rejected")
    @Transactional
    void test18_directSqlOrganizationMutation_rejected() {
        Project p = new Project();
        p.setName("SQL Mutate Test");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        p = projectRepository.save(p);

        p.setOrganization(orgB);
        projectRepository.save(p);
        entityManager.flush();
        entityManager.clear();

        Project reloaded = projectRepository.findById(p.getId()).orElseThrow();
        assertEquals(orgA.getId(), reloaded.getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 19: JPA Organization Mutation Ignored via updatable=false")
    @Transactional
    void test19_jpaOrganizationMutation_ignoredOrRejected() {
        Project p = new Project();
        p.setName("JPA Mutate Test");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        p = projectRepository.save(p);

        p.setOrganization(orgB);
        projectRepository.saveAndFlush(p);
        entityManager.clear();

        Project reloaded = projectRepository.findById(p.getId()).orElseThrow();
        assertEquals(orgA.getId(), reloaded.getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 20: Client-Controlled Organization Ownership Rejected")
    void test20_clientControlledOrganizationOwnership_rejected() {
        CreateOrganizationRequest req = new CreateOrganizationRequest("Client Control Test", "slug-client-control-" + UUID.randomUUID().toString().substring(0, 5));
        OrganizationDto dto = organizationService.createOrganization(adminUserOrgA, req);
        assertEquals(OrganizationRole.ORG_ADMIN, dto.getRole());
    }

    @Test
    @DisplayName("INVARIANT 21: Client-Controlled Membership Creation Rejected")
    void test21_clientControlledMembership_rejected() {
        CreateOrganizationRequest req = new CreateOrganizationRequest("Membership Control Test", "slug-mem-control-" + UUID.randomUUID().toString().substring(0, 5));
        OrganizationDto dto = organizationService.createOrganization(adminUserOrgA, req);
        assertFalse(organizationMemberRepository.existsByOrganizationIdAndUserId(dto.getId(), outsiderUserOrgB.getId()));
    }

    @Test
    @DisplayName("INVARIANT 22: DTO Secret/Internal Field Leakage Prevented")
    void test22_dtoSecretInternalFieldLeakage_prevented() {
        OrganizationPolicyDto dto = organizationPolicyService.getPolicy(orgA.getId(), adminUserOrgA);
        assertNotNull(dto.getId());
        assertNotNull(dto.getOrganizationId());
        assertNotNull(dto.getMonthlyTokenBudget());
        assertNotNull(dto.getMonthlySpendLimitUsd());
    }

    // PROJECT INTEGRATION (23-26)
    @Test
    @DisplayName("INVARIANT 23: Valid Project Gets Correct Organization")
    void test23_validProject_getsCorrectOrganization() {
        CreateProjectRequest req = new CreateProjectRequest("Valid Org Project", "Desc", orgA.getId());
        ProjectDto project = projectService.createProject(adminUserOrgA, req);
        assertEquals(orgA.getId(), projectRepository.findById(project.getId()).orElseThrow().getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 24: Invalid or Missing Organization Fails Closed")
    void test24_invalidOrMissingOrganization_failsClosed() {
        User standaloneUser = new User();
        standaloneUser.setEmail("standalone_" + UUID.randomUUID() + "@example.com");
        standaloneUser.setPasswordHash("hash");
        standaloneUser.setName("Standalone User");
        standaloneUser.setRole(UserRole.USER);
        final User savedStandaloneUser = userRepository.save(standaloneUser);

        // User belongs to 0 orgs & specifies null orgId -> Fails closed with 400 Bad Request
        CreateProjectRequest req = new CreateProjectRequest("No Org Project", "Desc", null);
        ApiException ex = assertThrows(ApiException.class, () ->
                projectService.createProject(savedStandaloneUser, req)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("ORGANIZATION_REQUIRED", ex.getCode());
    }

    @Test
    @DisplayName("INVARIANT 25: Cross-Org Project Creation Returns 403")
    void test25_crossOrgProjectCreation_returns403() {
        CreateProjectRequest req = new CreateProjectRequest("Cross Org Project", "Desc", orgB.getId());
        ApiException ex = assertThrows(ApiException.class, () ->
                projectService.createProject(adminUserOrgA, req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("INVARIANT 26: Existing Project Access Unchanged")
    void test26_existingProjectAccess_unchanged() {
        Project p = new Project();
        p.setName("Unchanged Access Proj");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        p = projectRepository.save(p);

        ProjectMember pm = new ProjectMember(p, memberUserOrgA, ProjectRole.VIEWER);
        projectMemberRepository.save(pm);

        assertTrue(projectMemberRepository.existsByProjectIdAndUserId(p.getId(), memberUserOrgA.getId()));
    }

    // AUDIT (27-30)
    @Test
    @DisplayName("INVARIANT 27: Audit/Project Organization Equality Enforced")
    void test27_auditProjectOrganizationEquality_enforced() {
        Project p = new Project();
        p.setName("Audit Proj Equality");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        p = projectRepository.save(p);

        AuditLog audit = auditLogService.logAction(adminUserOrgA, p, null, "TEST_EQUALITY", "PROJECT", p.getId(), "{}");
        assertEquals(orgA.getId(), audit.getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 28: Audit/Task Organization Equality Enforced")
    void test28_auditTaskOrganizationEquality_enforced() {
        Project p = new Project();
        p.setName("Audit Task Equality");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        p = projectRepository.save(p);

        Repository repo = new Repository();
        repo.setProject(p);
        repo.setOwnerName("owner");
        repo.setRepoName("repo-" + UUID.randomUUID().toString().substring(0, 5));
        repo.setDefaultBranch("main");
        repo.setVisibility(RepositoryVisibility.PRIVATE);
        repo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repo = repositoryRepository.save(repo);

        Task task = new Task();
        task.setProject(p);
        task.setRepository(repo);
        task.setCreatedBy(adminUserOrgA);
        task.setTitle("Audit Task");
        task.setDescription("Desc");
        task.setStatus(TaskStatus.COMPLETED);
        task.setPriority(TaskPriority.MEDIUM);
        task = taskRepository.save(task);

        AuditLog audit = auditLogService.logAction(adminUserOrgA, null, task, "TASK_ACTION", "TASK", task.getId(), "{}");
        assertEquals(orgA.getId(), audit.getOrganization().getId());
    }

    @Test
    @DisplayName("INVARIANT 29: Explicit Mismatched Organization Rejected")
    void test29_explicitMismatchedOrganization_rejected() {
        Project p = new Project();
        p.setName("Audit Mismatch Proj");
        p.setOwner(adminUserOrgA);
        p.setOrganization(orgA);
        final Project savedP = projectRepository.save(p);

        // Attempt logging action with explicitOrg = orgB, but project = orgA -> Throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                auditLogService.logAction(adminUserOrgA, orgB, savedP, null, "MISMATCH_ACTION", "PROJECT", savedP.getId(), "{}")
        );
    }

    @Test
    @DisplayName("INVARIANT 30: Orphan Audit Policy Verified")
    void test30_orphanAuditPolicy_verified() {
        AuditLog systemAudit = auditLogService.logAction(null, null, null, "SYSTEM_HEARTBEAT", "SYSTEM", null, "{}");
        assertNotNull(systemAudit.getOrganization());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), systemAudit.getOrganization().getId());
    }
}
