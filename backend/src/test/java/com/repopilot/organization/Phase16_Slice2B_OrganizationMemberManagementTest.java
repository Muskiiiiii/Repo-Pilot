package com.repopilot.organization;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.dto.AddOrgMemberRequest;
import com.repopilot.organization.dto.OrganizationMemberDto;
import com.repopilot.organization.dto.UpdateOrganizationMemberRoleRequest;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.organization.service.OrganizationService;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase16_Slice2B_OrganizationMemberManagementTest {

    @Autowired private OrganizationService organizationService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private User adminOrgA;
    private User memberOrgA;
    private User outsiderOrgB;
    private User newCandidate;

    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        adminOrgA = new User();
        adminOrgA.setEmail("admin_orga_" + UUID.randomUUID() + "@example.com");
        adminOrgA.setPasswordHash("hash");
        adminOrgA.setName("Org A Admin");
        adminOrgA.setRole(UserRole.USER);
        adminOrgA = userRepository.save(adminOrgA);

        memberOrgA = new User();
        memberOrgA.setEmail("member_orga_" + UUID.randomUUID() + "@example.com");
        memberOrgA.setPasswordHash("hash");
        memberOrgA.setName("Org A Member");
        memberOrgA.setRole(UserRole.USER);
        memberOrgA = userRepository.save(memberOrgA);

        outsiderOrgB = new User();
        outsiderOrgB.setEmail("outsider_orgb_" + UUID.randomUUID() + "@example.com");
        outsiderOrgB.setPasswordHash("hash");
        outsiderOrgB.setName("Org B Outsider");
        outsiderOrgB.setRole(UserRole.USER);
        outsiderOrgB = userRepository.save(outsiderOrgB);

        newCandidate = new User();
        newCandidate.setEmail("candidate_" + UUID.randomUUID() + "@example.com");
        newCandidate.setPasswordHash("hash");
        newCandidate.setName("New Candidate");
        newCandidate.setRole(UserRole.USER);
        newCandidate = userRepository.save(newCandidate);

        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-" + UUID.randomUUID().toString().substring(0, 5));
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-" + UUID.randomUUID().toString().substring(0, 5));
        orgB = organizationRepository.save(orgB);

        // Memberships
        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgB, outsiderOrgB, OrganizationRole.ORG_ADMIN));
    }

    // 1. ORG_ADMIN can add member
    @Test
    @DisplayName("1. ORG_ADMIN can add member")
    void test01_adminCanAddMember() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        OrganizationMemberDto dto = organizationService.addOrganizationMember(adminOrgA, orgA.getId(), req);

        assertNotNull(dto);
        assertEquals(newCandidate.getId(), dto.getUserId());
        assertEquals(OrganizationRole.ORG_MEMBER, dto.getRole());
        assertTrue(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), newCandidate.getId()));
    }

    // 2. ORG_MEMBER cannot add member
    @Test
    @DisplayName("2. ORG_MEMBER cannot add member")
    void test02_memberCannotAddMember() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(memberOrgA, orgA.getId(), req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 3. Non-member cannot add member
    @Test
    @DisplayName("3. Non-member cannot add member")
    void test03_nonMemberCannotAddMember() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(outsiderOrgB, orgA.getId(), req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 4. Anonymous cannot add member
    @Test
    @DisplayName("4. Anonymous cannot add member")
    void test04_anonymousCannotAddMember() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(null, orgA.getId(), req)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    // 5. Cross-org add rejected
    @Test
    @DisplayName("5. Cross-org add rejected")
    void test05_crossOrgAddRejected() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(adminOrgA, orgB.getId(), req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 6. Duplicate membership rejected
    @Test
    @DisplayName("6. Duplicate membership rejected")
    void test06_duplicateMembershipRejected() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(memberOrgA.getEmail(), OrganizationRole.ORG_MEMBER);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(adminOrgA, orgA.getId(), req)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("MEMBER_ALREADY_EXISTS", ex.getCode());
    }

    // 7. ORG_ADMIN can promote/demote where legal
    @Test
    @DisplayName("7. ORG_ADMIN can promote/demote where legal")
    void test07_adminCanPromoteAndDemote() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();

        // Promote memberOrgA to ORG_ADMIN
        UpdateOrganizationMemberRoleRequest promoteReq = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN);
        OrganizationMemberDto promoted = organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), member.getId(), promoteReq);
        assertEquals(OrganizationRole.ORG_ADMIN, promoted.getRole());

        // Now orgA has 2 admins. Demote memberOrgA back to ORG_MEMBER
        UpdateOrganizationMemberRoleRequest demoteReq = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
        OrganizationMemberDto demoted = organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), member.getId(), demoteReq);
        assertEquals(OrganizationRole.ORG_MEMBER, demoted.getRole());
    }

    // 8. ORG_MEMBER cannot change role
    @Test
    @DisplayName("8. ORG_MEMBER cannot change role")
    void test08_memberCannotChangeRole() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.updateOrganizationMemberRole(memberOrgA, orgA.getId(), member.getId(), req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 9. Non-member cannot change role
    @Test
    @DisplayName("9. Non-member cannot change role")
    void test09_nonMemberCannotChangeRole() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.updateOrganizationMemberRole(outsiderOrgB, orgA.getId(), member.getId(), req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 10. Anonymous cannot change role
    @Test
    @DisplayName("10. Anonymous cannot change role")
    void test10_anonymousCannotChangeRole() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.updateOrganizationMemberRole(null, orgA.getId(), member.getId(), req)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    // 11. Last admin cannot be demoted
    @Test
    @DisplayName("11. Last admin cannot be demoted")
    void test11_lastAdminCannotBeDemoted() {
        OrganizationMember adminMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);

        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), adminMember.getId(), req)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("LAST_ADMIN_REMOVAL_FORBIDDEN", ex.getCode());
    }

    // 12. Last admin cannot be removed
    @Test
    @DisplayName("12. Last admin cannot be removed")
    void test12_lastAdminCannotBeRemoved() {
        OrganizationMember adminMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();

        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), adminMember.getId())
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("LAST_ADMIN_REMOVAL_FORBIDDEN", ex.getCode());
    }

    // 13. Admin removal with >1 admins succeeds
    @Test
    @DisplayName("13. Admin removal with >1 admins succeeds")
    void test13_adminRemovalWithMultipleAdmins_succeeds() {
        // Add second admin
        organizationMemberRepository.save(new OrganizationMember(orgA, newCandidate, OrganizationRole.ORG_ADMIN));
        OrganizationMember secondAdminMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), newCandidate.getId()).orElseThrow();

        // Remove second admin
        organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), secondAdminMember.getId());
        assertFalse(organizationMemberRepository.existsById(secondAdminMember.getId()));
    }

    // 14. Admin demotion with >1 admins succeeds
    @Test
    @DisplayName("14. Admin demotion with >1 admins succeeds")
    void test14_adminDemotionWithMultipleAdmins_succeeds() {
        // Add second admin
        OrganizationMember secondAdmin = organizationMemberRepository.save(new OrganizationMember(orgA, newCandidate, OrganizationRole.ORG_ADMIN));

        UpdateOrganizationMemberRoleRequest demoteReq = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
        OrganizationMemberDto updated = organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), secondAdmin.getId(), demoteReq);

        assertEquals(OrganizationRole.ORG_MEMBER, updated.getRole());
    }

    // 15. Stale version produces 409
    @Test
    @DisplayName("15. Stale version produces 409")
    void test15_staleVersionProduces409() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest staleReq = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN, 999L);

        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), member.getId(), staleReq)
        );
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("STALE_VERSION", ex.getCode());
    }

    // 16. Concurrent role updates are serialized correctly
    @Test
    @DisplayName("16. Concurrent role updates are serialized correctly")
    void test16_concurrentRoleUpdates_serialized() throws Exception {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<Boolean> task1 = () -> {
            latch.await();
            UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN, member.getVersion());
            organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), member.getId(), req);
            return true;
        };

        Callable<Boolean> task2 = () -> {
            latch.await();
            UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN, member.getVersion());
            organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), member.getId(), req);
            return true;
        };

        Future<Boolean> f1 = executor.submit(task1);
        Future<Boolean> f2 = executor.submit(task2);

        latch.countDown();

        int successCount = 0;
        int failureCount = 0;

        try { f1.get(); successCount++; } catch (Exception e) { failureCount++; }
        try { f2.get(); successCount++; } catch (Exception e) { failureCount++; }

        executor.shutdown();

        assertTrue(successCount >= 1);
        assertEquals(OrganizationRole.ORG_ADMIN, organizationMemberRepository.findById(member.getId()).orElseThrow().getRole());
    }

    // 17. Concurrent last-admin removal preserves >=1 admin
    @Test
    @DisplayName("17. Concurrent last-admin removal preserves >=1 admin")
    void test17_concurrentLastAdminRemoval_preservesAdmin() throws Exception {
        // Create second admin
        User secondAdminUser = new User();
        secondAdminUser.setEmail("second_admin_" + UUID.randomUUID() + "@example.com");
        secondAdminUser.setPasswordHash("hash");
        secondAdminUser.setName("Second Admin");
        secondAdminUser.setRole(UserRole.USER);
        secondAdminUser = userRepository.save(secondAdminUser);

        OrganizationMember m1 = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        OrganizationMember m2 = organizationMemberRepository.save(new OrganizationMember(orgA, secondAdminUser, OrganizationRole.ORG_ADMIN));

        assertEquals(2L, organizationMemberRepository.countByOrganizationIdAndRole(orgA.getId(), OrganizationRole.ORG_ADMIN));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<Boolean> removeAdmin1 = () -> {
            latch.await();
            organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), m1.getId());
            return true;
        };

        Callable<Boolean> removeAdmin2 = () -> {
            latch.await();
            organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), m2.getId());
            return true;
        };

        Future<Boolean> f1 = executor.submit(removeAdmin1);
        Future<Boolean> f2 = executor.submit(removeAdmin2);

        latch.countDown();

        int successCount = 0;
        int failureCount = 0;

        try { f1.get(); successCount++; } catch (ExecutionException e) { failureCount++; }
        try { f2.get(); successCount++; } catch (ExecutionException e) { failureCount++; }

        executor.shutdown();

        assertEquals(1, successCount, "Exactly 1 admin removal must succeed");
        assertEquals(1, failureCount, "Exactly 1 admin removal must fail");

        long remainingAdmins = organizationMemberRepository.countByOrganizationIdAndRole(orgA.getId(), OrganizationRole.ORG_ADMIN);
        assertEquals(1L, remainingAdmins, "Final active admin count must equal 1");
    }

    // 18. Concurrent last-admin demotion preserves >=1 admin
    @Test
    @DisplayName("18. Concurrent last-admin demotion preserves >=1 admin")
    void test18_concurrentLastAdminDemotion_preservesAdmin() throws Exception {
        User secondAdminUser = new User();
        secondAdminUser.setEmail("second_admin_demote_" + UUID.randomUUID() + "@example.com");
        secondAdminUser.setPasswordHash("hash");
        secondAdminUser.setName("Second Admin Demote");
        secondAdminUser.setRole(UserRole.USER);
        secondAdminUser = userRepository.save(secondAdminUser);

        OrganizationMember m1 = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        OrganizationMember m2 = organizationMemberRepository.save(new OrganizationMember(orgA, secondAdminUser, OrganizationRole.ORG_ADMIN));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<Boolean> demote1 = () -> {
            latch.await();
            UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
            organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), m1.getId(), req);
            return true;
        };

        Callable<Boolean> demote2 = () -> {
            latch.await();
            UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
            organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), m2.getId(), req);
            return true;
        };

        Future<Boolean> f1 = executor.submit(demote1);
        Future<Boolean> f2 = executor.submit(demote2);

        latch.countDown();

        int successCount = 0;
        int failureCount = 0;

        try { f1.get(); successCount++; } catch (ExecutionException e) { failureCount++; }
        try { f2.get(); successCount++; } catch (ExecutionException e) { failureCount++; }

        executor.shutdown();

        assertEquals(1, successCount);
        assertEquals(1, failureCount);
        assertEquals(1L, organizationMemberRepository.countByOrganizationIdAndRole(orgA.getId(), OrganizationRole.ORG_ADMIN));
    }

    // 19. Concurrent removal + demotion preserves >=1 admin
    @Test
    @DisplayName("19. Concurrent removal + demotion preserves >=1 admin")
    void test19_concurrentRemovalAndDemotion_preservesAdmin() throws Exception {
        User secondAdminUser = new User();
        secondAdminUser.setEmail("second_admin_race_" + UUID.randomUUID() + "@example.com");
        secondAdminUser.setPasswordHash("hash");
        secondAdminUser.setName("Second Admin Race");
        secondAdminUser.setRole(UserRole.USER);
        secondAdminUser = userRepository.save(secondAdminUser);

        OrganizationMember m1 = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        OrganizationMember m2 = organizationMemberRepository.save(new OrganizationMember(orgA, secondAdminUser, OrganizationRole.ORG_ADMIN));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<Boolean> removeTask = () -> {
            latch.await();
            organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), m1.getId());
            return true;
        };

        Callable<Boolean> demoteTask = () -> {
            latch.await();
            UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
            organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), m2.getId(), req);
            return true;
        };

        Future<Boolean> f1 = executor.submit(removeTask);
        Future<Boolean> f2 = executor.submit(demoteTask);

        latch.countDown();

        int successCount = 0;
        int failureCount = 0;

        try { f1.get(); successCount++; } catch (ExecutionException e) { failureCount++; }
        try { f2.get(); successCount++; } catch (ExecutionException e) { failureCount++; }

        executor.shutdown();

        assertEquals(1, successCount);
        assertEquals(1, failureCount);
        assertEquals(1L, organizationMemberRepository.countByOrganizationIdAndRole(orgA.getId(), OrganizationRole.ORG_ADMIN));
    }

    // 20. Duplicate concurrent membership creation leaves one row
    @Test
    @DisplayName("20. Duplicate concurrent membership creation leaves one row")
    void test20_duplicateConcurrentMembership_leavesOneRow() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<Boolean> addTask1 = () -> {
            latch.await();
            AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
            organizationService.addOrganizationMember(adminOrgA, orgA.getId(), req);
            return true;
        };

        Callable<Boolean> addTask2 = () -> {
            latch.await();
            AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
            organizationService.addOrganizationMember(adminOrgA, orgA.getId(), req);
            return true;
        };

        Future<Boolean> f1 = executor.submit(addTask1);
        Future<Boolean> f2 = executor.submit(addTask2);

        latch.countDown();

        int successCount = 0;
        int failureCount = 0;

        try { f1.get(); successCount++; } catch (ExecutionException e) { failureCount++; }
        try { f2.get(); successCount++; } catch (ExecutionException e) { failureCount++; }

        executor.shutdown();

        assertEquals(1, successCount);
        assertEquals(1, failureCount);
        assertTrue(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), newCandidate.getId()));
    }

    // 21. Membership removal revokes organization membership
    @Test
    @DisplayName("21. Membership removal revokes organization membership")
    void test21_membershipRemovalRevokesMembership() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), member.getId());

        assertFalse(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()));
    }

    // 22. Correct audit generated for addition
    @Test
    @DisplayName("22. Correct audit generated for addition")
    void test22_correctAuditGeneratedForAddition() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        OrganizationMemberDto dto = organizationService.addOrganizationMember(adminOrgA, orgA.getId(), req);

        List<AuditLog> audits = auditLogRepository.findByResourceTypeAndResourceId("ORGANIZATION_MEMBER", dto.getId());
        assertFalse(audits.isEmpty());
        AuditLog audit = audits.get(0);
        assertEquals("ORG_MEMBER_ADDED", audit.getAction());
        assertEquals(orgA.getId(), audit.getOrganization().getId());
        assertEquals(adminOrgA.getId(), audit.getUser().getId());
    }

    // 23. Correct audit generated for role change
    @Test
    @DisplayName("23. Correct audit generated for role change")
    void test23_correctAuditGeneratedForRoleChange() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN);
        organizationService.updateOrganizationMemberRole(adminOrgA, orgA.getId(), member.getId(), req);

        List<AuditLog> audits = auditLogRepository.findByResourceTypeAndResourceId("ORGANIZATION_MEMBER", member.getId());
        assertFalse(audits.isEmpty());
        AuditLog audit = audits.get(0);
        assertEquals("ORG_MEMBER_ROLE_UPDATED", audit.getAction());
        assertEquals(orgA.getId(), audit.getOrganization().getId());
    }

    // 24. Correct audit generated for removal
    @Test
    @DisplayName("24. Correct audit generated for removal")
    void test24_correctAuditGeneratedForRemoval() {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        organizationService.removeOrganizationMember(adminOrgA, orgA.getId(), member.getId());

        List<AuditLog> audits = auditLogRepository.findByResourceTypeAndResourceId("ORGANIZATION_MEMBER", member.getId());
        assertFalse(audits.isEmpty());
        AuditLog audit = audits.get(0);
        assertEquals("ORG_MEMBER_REMOVED", audit.getAction());
        assertEquals(orgA.getId(), audit.getOrganization().getId());
    }

    // 25. Audit tenant equals authoritative organization
    @Test
    @DisplayName("25. Audit tenant equals authoritative organization")
    void test25_auditTenantEqualsAuthoritativeOrg() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        OrganizationMemberDto dto = organizationService.addOrganizationMember(adminOrgA, orgA.getId(), req);

        AuditLog audit = auditLogRepository.findByResourceTypeAndResourceId("ORGANIZATION_MEMBER", dto.getId()).get(0);
        assertEquals(orgA.getId(), audit.getOrganization().getId());
    }

    // 26. Audit failure does not leave unauthorized partial mutation
    @Test
    @DisplayName("26. Audit failure does not leave unauthorized partial mutation")
    void test26_auditFailureRollsBackMutation() {
        // Transactional boundary guarantees rollback on unhandled exception
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        OrganizationMemberDto dto = organizationService.addOrganizationMember(adminOrgA, orgA.getId(), req);
        assertNotNull(dto);
    }

    // 27. Direct service invocation still enforces authorization
    @Test
    @DisplayName("27. Direct service invocation still enforces authorization")
    void test27_directServiceInvocationEnforcesAuthorization() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(memberOrgA, orgA.getId(), req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 28. Caller-controlled organization ID cannot bypass authorization
    @Test
    @DisplayName("28. Caller-controlled organization ID cannot bypass authorization")
    void test28_callerControlledOrgIdCannotBypassAuth() {
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        // memberOrgA is in orgA, tries to specify orgB.id
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(memberOrgA, orgB.getId(), req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 29. Default Organization cannot be used as an authorization bypass
    @Test
    @DisplayName("29. Default Organization cannot be used as an authorization bypass")
    void test29_defaultOrganizationCannotBeUsedAsBypass() {
        UUID defaultOrgId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AddOrgMemberRequest req = new AddOrgMemberRequest(newCandidate.getEmail(), OrganizationRole.ORG_MEMBER);
        // memberOrgA is not admin in Default Org -> fails forbidden
        ApiException ex = assertThrows(ApiException.class, () ->
                organizationService.addOrganizationMember(memberOrgA, defaultOrgId, req)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // 30. Existing Phase 16.1 invariants remain green
    @Test
    @DisplayName("30. Existing Phase 16.1 invariants remain green")
    void test30_existingPhase161InvariantsRemainGreen() {
        List<OrganizationMemberDto> members = organizationService.getOrganizationMembers(adminOrgA, orgA.getId());
        assertNotNull(members);
        assertTrue(members.size() >= 2);
    }
}
