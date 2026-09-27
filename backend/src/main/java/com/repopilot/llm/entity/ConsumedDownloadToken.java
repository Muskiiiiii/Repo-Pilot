package com.repopilot.llm.entity;

import com.repopilot.organization.entity.Organization;
import com.repopilot.user.entity.User;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "consumed_download_tokens")
public class ConsumedDownloadToken implements Persistable<String> {

    @Id
    @Column(name = "jti_hash", nullable = false, length = 64)
    private String jtiHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private AuditExportJob job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public ConsumedDownloadToken() {
    }

    public ConsumedDownloadToken(String jtiHash, AuditExportJob job, Organization organization, User user, Instant expiresAt) {
        this.jtiHash = jtiHash;
        this.job = job;
        this.organization = organization;
        this.user = user;
        this.consumedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    @Override
    public String getId() {
        return jtiHash;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    public String getJtiHash() {
        return jtiHash;
    }

    public void setJtiHash(String jtiHash) {
        this.jtiHash = jtiHash;
    }

    public AuditExportJob getJob() {
        return job;
    }

    public void setJob(AuditExportJob job) {
        this.job = job;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
