package com.repopilot.project.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectAuthorizationServiceImpl implements ProjectAuthorizationService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;

    public ProjectAuthorizationServiceImpl(ProjectRepository projectRepository,
                                           ProjectMemberRepository projectMemberRepository,
                                           OrganizationAuthorizationService organizationAuthorizationService) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public Project getAuthorizedProject(UUID projectId, User user) {
        if (projectId == null || user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));

        if (!hasAccess(project, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not authorized to access this project");
        }

        return project;
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectRole getUserRoleInProject(Project project, User user) {
        if (project == null || user == null) return null;
        Project loadedProject = (project.getId() != null) ? projectRepository.findById(project.getId()).orElse(project) : project;

        // Dual-key prerequisite: Organization membership required
        if (loadedProject.getOrganization() == null || loadedProject.getOrganization().getId() == null) {
            return null;
        }
        if (!organizationAuthorizationService.hasAccess(loadedProject.getOrganization().getId(), user)) {
            return null;
        }

        // Project role authorization
        if (loadedProject.getOwner() != null && loadedProject.getOwner().getId().equals(user.getId())) {
            return ProjectRole.OWNER;
        }

        Optional<ProjectMember> memberOpt = projectMemberRepository.findByProjectIdAndUserId(loadedProject.getId(), user.getId());
        return memberOpt.map(ProjectMember::getRole).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAccess(Project project, User user) {
        if (project == null || user == null) return false;
        Project loadedProject = (project.getId() != null) ? projectRepository.findById(project.getId()).orElse(project) : project;

        // Dual-key prerequisite: Organization membership required
        if (loadedProject.getOrganization() == null || loadedProject.getOrganization().getId() == null) {
            return false;
        }
        if (!organizationAuthorizationService.hasAccess(loadedProject.getOrganization().getId(), user)) {
            return false;
        }

        // Project access authorization
        if (loadedProject.getOwner() != null && loadedProject.getOwner().getId().equals(user.getId())) {
            return true;
        }
        return projectMemberRepository.existsByProjectIdAndUserId(loadedProject.getId(), user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public void requireMinimumRole(Project project, User user, ProjectRole minimumRole) {
        ProjectRole userRole = getUserRoleInProject(project, user);

        if (userRole == null || getRoleLevel(userRole) < getRoleLevel(minimumRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions for this project operation");
        }
    }

    private int getRoleLevel(ProjectRole role) {
        if (role == null) return 0;
        return switch (role) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case MEMBER -> 2;
            case VIEWER -> 1;
        };
    }
}
