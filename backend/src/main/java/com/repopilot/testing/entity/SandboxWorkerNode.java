package com.repopilot.testing.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sandbox_worker_nodes")
public class SandboxWorkerNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "node_identifier", nullable = false, unique = true, length = 100)
    private String nodeIdentifier;

    @Column(name = "socket_path", nullable = false, length = 255)
    private String socketPath;

    @Column(name = "max_concurrent_containers", nullable = false)
    private Integer maxConcurrentContainers = 4;

    @Column(name = "active_containers", nullable = false)
    private Integer activeContainers = 0;

    @Column(nullable = false, length = 30)
    private String status = "HEALTHY";

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt = Instant.now();

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public SandboxWorkerNode() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNodeIdentifier() {
        return nodeIdentifier;
    }

    public void setNodeIdentifier(String nodeIdentifier) {
        this.nodeIdentifier = nodeIdentifier;
    }

    public String getSocketPath() {
        return socketPath;
    }

    public void setSocketPath(String socketPath) {
        this.socketPath = socketPath;
    }

    public Integer getMaxConcurrentContainers() {
        return maxConcurrentContainers;
    }

    public void setMaxConcurrentContainers(Integer maxConcurrentContainers) {
        this.maxConcurrentContainers = maxConcurrentContainers;
    }

    public Integer getActiveContainers() {
        return activeContainers;
    }

    public void setActiveContainers(Integer activeContainers) {
        this.activeContainers = activeContainers;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
