package com.repopilot.project.service;

import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface ProjectAuthorizationService {

    Project getAuthorizedProject(UUID projectId, User user);

    ProjectRole getUserRoleInProject(Project project, User user);

    boolean hasAccess(Project project, User user);

    void requireMinimumRole(Project project, User user, ProjectRole minimumRole);
}
