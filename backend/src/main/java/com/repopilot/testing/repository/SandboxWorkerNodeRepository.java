package com.repopilot.testing.repository;

import com.repopilot.testing.entity.SandboxWorkerNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SandboxWorkerNodeRepository extends JpaRepository<SandboxWorkerNode, UUID> {

    Optional<SandboxWorkerNode> findByNodeIdentifier(String nodeIdentifier);

    @org.springframework.data.jpa.repository.Query("SELECT w FROM SandboxWorkerNode w WHERE w.lastHeartbeatAt < :cutoff AND w.status != 'DEAD'")
    java.util.List<SandboxWorkerNode> findStaleWorkers(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
}
