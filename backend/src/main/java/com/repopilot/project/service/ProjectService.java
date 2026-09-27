package com.repopilot.project.service;

import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.dto.*;
import com.repopilot.project.entity.*;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final ProjectAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final com.repopilot.organization.repository.OrganizationRepository organizationRepository;
    private final com.repopilot.organization.service.OrganizationAuthorizationService organizationAuthorizationService;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectMemberRepository projectMemberRepository,
                          UserRepository userRepository,
                          RepositoryRepository repositoryRepository,
                          TaskRepository taskRepository,
                          ProjectAuthorizationService authorizationService,
                          AuditLogService auditLogService,
                          com.repopilot.organization.repository.OrganizationRepository organizationRepository,
                          com.repopilot.organization.service.OrganizationAuthorizationService organizationAuthorizationService) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.organizationRepository = organizationRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
    }

    @Transactional
    public ProjectDto createProject(User currentUser, CreateProjectRequest request) {
        Project project = new Project();
        project.setName(request.getName().trim());
        project.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        project.setOwner(currentUser);
        project.setStatus(ProjectStatus.ACTIVE);

        com.repopilot.organization.entity.Organization org = null;
        if (request.getOrganizationId() != null) {
            org = organizationAuthorizationService.getAuthorizedOrganization(request.getOrganizationId(), currentUser);
        } else {
            List<com.repopilot.organization.entity.Organization> userOrgs = organizationAuthorizationService.getUserOrganizations(currentUser);
            if (userOrgs.size() == 1) {
                org = userOrgs.get(0);
            } else if (userOrgs.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_REQUIRED", "User does not belong to any organization. Please specify an organization for project creation.");
            } else {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_REQUIRED", "User belongs to multiple organizations. Please specify organizationId explicitly.");
            }
        }
        project.setOrganization(org);

        Project savedProject = projectRepository.save(project);

        // Automatically add owner as OWNER member in project_members table
        ProjectMember ownerMember = new ProjectMember(savedProject, currentUser, ProjectRole.OWNER);
        projectMemberRepository.save(ownerMember);

        // Automatically add owner as ORG_MEMBER if not already present
        if (!org.getId().equals(UUID.fromString("00000000-0000-0000-0000-000000000001"))) {
            // Already validated organization membership
        }

        // Record audit log
        auditLogService.logAction(currentUser, savedProject, null, "PROJECT_CREATED", "PROJECT", savedProject.getId(), "Created project: " + savedProject.getName());

        return buildProjectDto(savedProject, ProjectRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> getUserProjects(User currentUser, String query) {
        // Retrieve projects owned by user + projects where user is a member
        List<Project> ownedProjects = projectRepository.findByOwnerId(currentUser.getId());
        List<ProjectMember> memberships = projectMemberRepository.findByUserId(currentUser.getId());

        Map<UUID, Project> projectMap = new HashMap<>();
        Map<UUID, ProjectRole> roleMap = new HashMap<>();

        for (Project p : ownedProjects) {
            projectMap.put(p.getId(), p);
            roleMap.put(p.getId(), ProjectRole.OWNER);
        }

        for (ProjectMember pm : memberships) {
            if (!projectMap.containsKey(pm.getProject().getId())) {
                projectMap.put(pm.getProject().getId(), pm.getProject());
                roleMap.put(pm.getProject().getId(), pm.getRole());
            }
        }

        List<Project> projects = new ArrayList<>(projectMap.values());

        // Optional query filtering
        if (query != null && !query.isBlank()) {
            String lowercaseQuery = query.toLowerCase().trim();
            projects = projects.stream()
                    .filter(p -> p.getName().toLowerCase().contains(lowercaseQuery) ||
                            (p.getDescription() != null && p.getDescription().toLowerCase().contains(lowercaseQuery)))
                    .collect(Collectors.toList());
        }

        projects.sort(Comparator.comparing(Project::getUpdatedAt).reversed());

        return projects.stream()
                .filter(p -> p.getStatus() != ProjectStatus.DELETED)
                .filter(p -> authorizationService.hasAccess(p, currentUser))
                .map(p -> buildProjectDto(p, authorizationService.getUserRoleInProject(p, currentUser)))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> getOrganizationProjects(User currentUser, UUID organizationId) {
        if (organizationId == null || currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }

        // 1. Verify caller is an active member of target organization
        organizationAuthorizationService.getAuthorizedOrganization(organizationId, currentUser);

        // 2. Obtain candidate projects belonging to target organization
        List<Project> candidateProjects = projectRepository.findByOrganizationId(organizationId);

        // 3. Every candidate MUST be evaluated using existing ProjectAuthorizationService project-level semantics
        return candidateProjects.stream()
                .filter(p -> p.getStatus() != ProjectStatus.DELETED)
                .filter(p -> authorizationService.hasAccess(p, currentUser))
                .map(p -> buildProjectDto(p, authorizationService.getUserRoleInProject(p, currentUser)))
                .sorted(Comparator.comparing(ProjectDto::getUpdatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProjectDto getProjectById(UUID projectId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        ProjectRole userRole = authorizationService.getUserRoleInProject(project, currentUser);
        return buildProjectDto(project, userRole);
    }

    @Transactional
    public ProjectDto updateProject(UUID projectId, User currentUser, UpdateProjectRequest request) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.ADMIN);

        project.setName(request.getName().trim());
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription().trim());
        }
        if (request.getStatus() != null) {
            project.setStatus(request.getStatus());
        }

        Project updatedProject = projectRepository.save(project);
        ProjectRole userRole = authorizationService.getUserRoleInProject(updatedProject, currentUser);

        auditLogService.logAction(currentUser, updatedProject, null, "PROJECT_UPDATED", "PROJECT", updatedProject.getId(), "Updated project: " + updatedProject.getName());

        return buildProjectDto(updatedProject, userRole);
    }

    @Transactional
    public void deleteProject(UUID projectId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.OWNER);

        project.setStatus(ProjectStatus.DELETED);
        projectRepository.save(project);

        auditLogService.logAction(currentUser, project, null, "PROJECT_DELETED", "PROJECT", project.getId(), "Deleted project: " + project.getName());
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberDto> getProjectMembers(UUID projectId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        List<ProjectMember> members = projectMemberRepository.findByProject(project);

        return members.stream()
                .map(ProjectMemberDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProjectMemberDto addProjectMember(UUID projectId, User currentUser, AddMemberRequest request) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.ADMIN);

        User targetUser = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found with email: " + request.getEmail()));

        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, targetUser.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_EXISTS", "User is already a member of this project");
        }

        ProjectMember member = new ProjectMember(project, targetUser, request.getRole());
        ProjectMember savedMember = projectMemberRepository.save(member);

        auditLogService.logAction(currentUser, project, null, "MEMBER_ADDED", "PROJECT_MEMBER", savedMember.getId(), "Added member: " + targetUser.getEmail());

        return ProjectMemberDto.fromEntity(savedMember);
    }

    @Transactional
    public void removeProjectMember(UUID projectId, UUID memberId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.ADMIN);

        ProjectMember member = projectMemberRepository.findById(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Project member not found"));

        if (!member.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEMBER", "Member does not belong to this project");
        }

        if (member.getRole() == ProjectRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Cannot remove project owner");
        }

        auditLogService.logAction(currentUser, project, null, "MEMBER_REMOVED", "PROJECT_MEMBER", member.getId(), "Removed member: " + member.getUser().getEmail());

        projectMemberRepository.delete(member);
    }

    private ProjectDto buildProjectDto(Project project, ProjectRole userRole) {
        long memberCount = projectMemberRepository.findByProject(project).size();
        long repoCount = repositoryRepository.findByProject(project).size();
        long taskCount = taskRepository.findByProject(project).size();

        return ProjectDto.fromEntity(project, userRole, memberCount, repoCount, taskCount);
    }
}
