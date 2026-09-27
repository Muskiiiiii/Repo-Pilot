package com.repopilot.github.dto;

import com.repopilot.repository.entity.RepositoryBranch;

import java.util.UUID;

public class BranchDto {

    private UUID id;
    private String name;
    private String commitSha;
    private boolean isDefault;

    public BranchDto() {
    }

    public BranchDto(UUID id, String name, String commitSha, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.commitSha = commitSha;
        this.isDefault = isDefault;
    }

    public static BranchDto fromEntity(RepositoryBranch branch) {
        return new BranchDto(
                branch.getId(),
                branch.getName(),
                branch.getCommitSha(),
                Boolean.TRUE.equals(branch.getIsDefault())
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }
}
