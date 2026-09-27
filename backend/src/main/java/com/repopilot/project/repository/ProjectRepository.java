package com.repopilot.project.repository;

import com.repopilot.project.entity.Project;
import com.repopilot.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByOwner(User owner);

    List<Project> findByOwnerId(UUID ownerId);

    List<Project> findByOrganizationId(UUID organizationId);
}
