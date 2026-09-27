package com.repopilot.organization;

import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.dto.AddOrgMemberRequest;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.organization.service.OrganizationService;
import com.repopilot.project.dto.CreateProjectRequest;
import com.repopilot.project.dto.ProjectDto;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.project.service.ProjectService;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase16_Slice2C_ProjectAuthorizationDualKeyTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private OrganizationAuthorizationService organizationAuthorizationService;

    @Autowired
    private ProjectAuthorizationService projectAuthorizationService;

    @Autowired
    private ProjectService projectService;

    private Organization orgA;
    private Organization orgB;

    private User adminOrgA;
    private User memberOrgA;
    private User userNoOrg;
    private User adminOrgB;

    private Project projA1;
    private Project projB1;

    @BeforeEach
    void setUp() {
        // Setup users
        adminOrgA = createUser("adminOrgA@example.com", "Admin OrgA");
        memberOrgA = createUser("memberOrgA@example.com", "Member OrgA");
        userNoOrg = createUser("userNoOrg@example.com", "User NoOrg");
        adminOrgB = createUser("adminOrgB@example.com", "Admin OrgB");

        // Setup organizations
        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-" + UUID.randomUUID().toString().substring(0, 8));
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-" + UUID.randomUUID().toString().substring(0, 8));
        orgB = organizationRepository.save(orgB);

        // Add org members
        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN));

        // Create projects
        projA1 = new Project();
        projA1.setName("Project A1");
        projA1.setOwner(adminOrgA);
        projA1.setOrganization(orgA);
        projA1 = projectRepository.save(projA1);
        projectMemberRepository.save(new ProjectMember(projA1, adminOrgA, ProjectRole.OWNER));

        projB1 = new Project();
        projB1.setName("Project B1");
        projB1.setOwner(adminOrgB);
        projB1.setOrganization(orgB);
        projB1 = projectRepository.save(projB1);
        projectMemberRepository.save(new ProjectMember(projB1, adminOrgB, ProjectRole.OWNER));
    }

    private User createUser(String email, String name) {
        User user = new User();
        user.setEmail(email.toLowerCase().trim());
        user.setName(name);
        user.setPasswordHash("hash_" + UUID.randomUUID());
        user.setRole(com.repopilot.user.entity.UserRole.USER);
        return userRepository.save(user);
    }

    // 1. Active org member + project member -> access granted
    @Test
    @DisplayName("1. Active org member + project member -> access granted")
    void test1_activeOrgMemberAndProjectMember_accessGranted() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));

        assertTrue(projectAuthorizationService.hasAccess(projA1, memberOrgA));
        assertNotNull(projectAuthorizationService.getAuthorizedProject(projA1.getId(), memberOrgA));
        assertEquals(ProjectRole.MEMBER, projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
    }

    // 2. Active org member + project viewer -> viewer access
    @Test
    @DisplayName("2. Active org member + project viewer -> viewer access")
    void test2_activeOrgMemberAndProjectViewer_viewerAccess() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.VIEWER));

        assertTrue(projectAuthorizationService.hasAccess(projA1, memberOrgA));
        assertEquals(ProjectRole.VIEWER, projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.VIEWER));
        assertThrows(ApiException.class, () -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.MEMBER));
    }

    // 3. Active org member + project admin -> admin access
    @Test
    @DisplayName("3. Active org member + project admin -> admin access")
    void test3_activeOrgMemberAndProjectAdmin_adminAccess() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.ADMIN));

        assertTrue(projectAuthorizationService.hasAccess(projA1, memberOrgA));
        assertEquals(ProjectRole.ADMIN, projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.ADMIN));
    }

    // 4. Active org member + project owner -> owner access
    @Test
    @DisplayName("4. Active org member + project owner -> owner access")
    void test4_activeOrgMemberAndProjectOwner_ownerAccess() {
        assertTrue(projectAuthorizationService.hasAccess(projA1, adminOrgA));
        assertEquals(ProjectRole.OWNER, projectAuthorizationService.getUserRoleInProject(projA1, adminOrgA));
        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, adminOrgA, ProjectRole.OWNER));
    }

    // 5. Org member with no project membership -> denied
    @Test
    @DisplayName("5. Org member with no project membership -> denied")
    void test5_orgMemberWithoutProjectMembership_denied() {
        assertFalse(projectAuthorizationService.hasAccess(projA1, memberOrgA));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projA1.getId(), memberOrgA));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 6. Org admin with no project membership -> denied
    @Test
    @DisplayName("6. Org admin with no project membership -> denied")
    void test6_orgAdminWithoutProjectMembership_denied() {
        User otherOrgAdmin = createUser("otherAdmin@example.com", "Other Admin");
        organizationMemberRepository.save(new OrganizationMember(orgA, otherOrgAdmin, OrganizationRole.ORG_ADMIN));

        assertFalse(projectAuthorizationService.hasAccess(projA1, otherOrgAdmin));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, otherOrgAdmin));
        assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projA1.getId(), otherOrgAdmin));
    }

    // 7. Non-member with project membership -> denied
    @Test
    @DisplayName("7. Non-member with project membership -> denied")
    void test7_nonOrgMemberWithProjectMembership_denied() {
        projectMemberRepository.save(new ProjectMember(projA1, userNoOrg, ProjectRole.MEMBER));

        assertFalse(projectAuthorizationService.hasAccess(projA1, userNoOrg));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, userNoOrg));
        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projA1.getId(), userNoOrg));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 8. User from Org A accessing Project B -> denied
    @Test
    @DisplayName("8. User from Org A accessing Project B -> denied")
    void test8_userOrgAAccessingProjectB_denied() {
        projectMemberRepository.save(new ProjectMember(projB1, adminOrgA, ProjectRole.MEMBER));

        assertFalse(projectAuthorizationService.hasAccess(projB1, adminOrgA));
        assertNull(projectAuthorizationService.getUserRoleInProject(projB1, adminOrgA));
        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projB1.getId(), adminOrgA));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 9. Removed org member with stale project membership -> denied
    @Test
    @DisplayName("9. Removed org member with stale project membership -> denied")
    void test9_removedOrgMemberWithStaleProjectMembership_denied() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));
        assertTrue(projectAuthorizationService.hasAccess(projA1, memberOrgA));

        // Remove from organization
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), member.getId());

        // Stale project membership row still exists
        assertTrue(projectMemberRepository.existsByProjectIdAndUserId(projA1.getId(), memberOrgA.getId()));

        // Access must immediately be denied
        assertFalse(projectAuthorizationService.hasAccess(projA1, memberOrgA));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projA1.getId(), memberOrgA));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 10. Re-added org member with valid project membership -> access restored
    @Test
    @DisplayName("10. Re-added org member with valid project membership -> access restored")
    void test10_reAddedOrgMember_accessRestored() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));

        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), member.getId());
        assertFalse(projectAuthorizationService.hasAccess(projA1, memberOrgA));

        // Re-add to organization
        organizationService.addOrganizationMember(adminOrgA, orgA.getId(), new AddOrgMemberRequest(memberOrgA.getEmail(), OrganizationRole.ORG_MEMBER));

        // Access restored
        assertTrue(projectAuthorizationService.hasAccess(projA1, memberOrgA));
        assertEquals(ProjectRole.MEMBER, projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
    }

    // 11. Null principal -> denied
    @Test
    @DisplayName("11. Null principal -> denied")
    void test11_nullPrincipal_denied() {
        assertFalse(projectAuthorizationService.hasAccess(projA1, null));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, null));
        assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projA1.getId(), null));
        assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(null, adminOrgA));
    }

    // 12. Non-existent project -> 404 NOT_FOUND
    @Test
    @DisplayName("12. Non-existent project -> 404 NOT_FOUND")
    void test12_nonExistentProject_notFound() {
        UUID fakeId = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(fakeId, adminOrgA));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // 13. Project with invalid/missing organization -> fail closed
    @Test
    @DisplayName("13. Project with invalid/missing organization -> fail closed")
    void test13_projectWithMissingOrganization_failClosed() {
        Project orphanProject = new Project();
        orphanProject.setName("Orphan");
        orphanProject.setOwner(adminOrgA);
        orphanProject.setOrganization(null); // Missing org
        final Project savedOrphan = projectRepository.save(orphanProject);

        assertFalse(projectAuthorizationService.hasAccess(savedOrphan, adminOrgA));
        assertNull(projectAuthorizationService.getUserRoleInProject(savedOrphan, adminOrgA));
        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(savedOrphan.getId(), adminOrgA));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 14. getAuthorizedProject enforces org gate
    @Test
    @DisplayName("14. getAuthorizedProject enforces org gate")
    void test14_getAuthorizedProjectEnforcesOrgGate() {
        projectMemberRepository.save(new ProjectMember(projA1, userNoOrg, ProjectRole.MEMBER));

        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projA1.getId(), userNoOrg));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 15. hasAccess enforces org gate
    @Test
    @DisplayName("15. hasAccess enforces org gate")
    void test15_hasAccessEnforcesOrgGate() {
        projectMemberRepository.save(new ProjectMember(projA1, userNoOrg, ProjectRole.MEMBER));

        assertFalse(projectAuthorizationService.hasAccess(projA1, userNoOrg));
    }

    // 16. Existing project role checks remain unchanged
    @Test
    @DisplayName("16. Existing project role checks remain unchanged")
    void test16_existingProjectRoleChecksUnchanged() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));

        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.MEMBER));
        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.VIEWER));
        assertThrows(ApiException.class, () -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.ADMIN));
    }

    // 17. OWNER semantics remain unchanged
    @Test
    @DisplayName("17. OWNER semantics remain unchanged")
    void test17_ownerSemanticsUnchanged() {
        assertEquals(ProjectRole.OWNER, projectAuthorizationService.getUserRoleInProject(projA1, adminOrgA));
        assertTrue(projectAuthorizationService.hasAccess(projA1, adminOrgA));
    }

    // 18. ADMIN semantics remain unchanged
    @Test
    @DisplayName("18. ADMIN semantics remain unchanged")
    void test18_adminSemanticsUnchanged() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.ADMIN));

        assertEquals(ProjectRole.ADMIN, projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.ADMIN));
        assertThrows(ApiException.class, () -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.OWNER));
    }

    // 19. MEMBER semantics remain unchanged
    @Test
    @DisplayName("19. MEMBER semantics remain unchanged")
    void test19_memberSemanticsUnchanged() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));

        assertEquals(ProjectRole.MEMBER, projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.MEMBER));
        assertThrows(ApiException.class, () -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.ADMIN));
    }

    // 20. VIEWER semantics remain unchanged
    @Test
    @DisplayName("20. VIEWER semantics remain unchanged")
    void test20_viewerSemanticsUnchanged() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.VIEWER));

        assertEquals(ProjectRole.VIEWER, projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
        assertDoesNotThrow(() -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.VIEWER));
        assertThrows(ApiException.class, () -> projectAuthorizationService.requireMinimumRole(projA1, memberOrgA, ProjectRole.MEMBER));
    }

    // 21. ORG_MEMBER does not imply project MEMBER
    @Test
    @DisplayName("21. ORG_MEMBER does not imply project MEMBER")
    void test21_orgMemberDoesNotImplyProjectMember() {
        // memberOrgA is ORG_MEMBER of orgA, but has no project membership in projA1
        assertFalse(projectAuthorizationService.hasAccess(projA1, memberOrgA));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, memberOrgA));
    }

    // 22. ORG_ADMIN does not imply project ADMIN
    @Test
    @DisplayName("22. ORG_ADMIN does not imply project ADMIN")
    void test22_orgAdminDoesNotImplyProjectAdmin() {
        User orgAdmin2 = createUser("orgAdmin2@example.com", "Org Admin 2");
        organizationMemberRepository.save(new OrganizationMember(orgA, orgAdmin2, OrganizationRole.ORG_ADMIN));

        assertFalse(projectAuthorizationService.hasAccess(projA1, orgAdmin2));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, orgAdmin2));
    }

    // 23. Cross-tenant project ID cannot leak project data
    @Test
    @DisplayName("23. Cross-tenant project ID cannot leak project data")
    void test23_crossTenantProjectId_cannotLeakProjectData() {
        ApiException ex = assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projB1.getId(), adminOrgA));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getCode());
    }

    // 24. Forged organization context cannot bypass project authorization
    @Test
    @DisplayName("24. Forged organization context cannot bypass project authorization")
    void test24_forgedOrganizationContext_cannotBypassProjectAuth() {
        // Even if user is member of orgB, project A belongs to orgA
        assertFalse(projectAuthorizationService.hasAccess(projA1, adminOrgB));
        assertThrows(ApiException.class, () -> projectAuthorizationService.getAuthorizedProject(projA1.getId(), adminOrgB));
    }

    // 25. Organization membership removal immediately blocks access
    @Test
    @DisplayName("25. Organization membership removal immediately blocks access")
    void test25_organizationMembershipRemoval_immediatelyBlocksAccess() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));
        assertTrue(projectAuthorizationService.hasAccess(projA1, memberOrgA));

        // Delete org membership row
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        organizationMemberRepository.delete(member);

        // Immediate block
        assertFalse(projectAuthorizationService.hasAccess(projA1, memberOrgA));
    }

    // 26. Stale project_members row cannot bypass org authorization
    @Test
    @DisplayName("26. Stale project_members row cannot bypass org authorization")
    void test26_staleProjectMembersRow_cannotBypassOrgAuth() {
        // Manually insert stale project_members row for non-org user
        projectMemberRepository.save(new ProjectMember(projA1, userNoOrg, ProjectRole.OWNER));

        assertFalse(projectAuthorizationService.hasAccess(projA1, userNoOrg));
        assertNull(projectAuthorizationService.getUserRoleInProject(projA1, userNoOrg));
    }

    // 27. Project organization remains immutable
    @Test
    @DisplayName("27. Project organization remains immutable")
    void test27_projectOrganizationRemainsImmutable() {
        assertEquals(orgA.getId(), projA1.getOrganization().getId());
    }

    // 28. getOrganizationProjects evaluates candidate projects via dual-key auth
    @Test
    @DisplayName("28. getOrganizationProjects evaluates candidate projects via dual-key auth")
    void test28_getOrganizationProjects_evaluatesCandidatesViaDualKeyAuth() {
        // Add memberOrgA to projA1
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));

        // Create second project in orgA where memberOrgA is NOT a member
        Project projA2 = new Project();
        projA2.setName("Project A2");
        projA2.setOwner(adminOrgA);
        projA2.setOrganization(orgA);
        projA2 = projectRepository.save(projA2);

        List<ProjectDto> memberProjects = projectService.getOrganizationProjects(memberOrgA, orgA.getId());
        assertEquals(1, memberProjects.size());
        assertEquals(projA1.getId(), memberProjects.get(0).getId());

        List<ProjectDto> adminProjects = projectService.getOrganizationProjects(adminOrgA, orgA.getId());
        assertEquals(2, adminProjects.size());
    }

    // 29. getUserProjects filters out projects where org access is revoked
    @Test
    @DisplayName("29. getUserProjects filters out projects where org access is revoked")
    void test29_getUserProjects_filtersRevokedOrgAccess() {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));
        List<ProjectDto> listBefore = projectService.getUserProjects(memberOrgA, null);
        assertEquals(1, listBefore.size());

        // Revoke org membership
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        organizationMemberRepository.delete(member);

        List<ProjectDto> listAfter = projectService.getUserProjects(memberOrgA, null);
        assertEquals(0, listAfter.size());
    }

    // 30. Non-member calling getOrganizationProjects -> 403 FORBIDDEN
    @Test
    @DisplayName("30. Non-member calling getOrganizationProjects -> 403 FORBIDDEN")
    void test30_nonMemberCallingGetOrganizationProjects_forbidden() {
        ApiException ex = assertThrows(ApiException.class, () -> projectService.getOrganizationProjects(userNoOrg, orgA.getId()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}
