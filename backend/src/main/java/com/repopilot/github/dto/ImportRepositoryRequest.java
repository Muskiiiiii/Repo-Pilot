package com.repopilot.github.dto;

import jakarta.validation.constraints.NotBlank;

public class ImportRepositoryRequest {

    private String githubRepoId;

    @NotBlank(message = "Owner name is required")
    private String ownerName;

    @NotBlank(message = "Repository name is required")
    private String repoName;

    private String defaultBranch = "main";
    private String cloneUrl;
    private String webUrl;
    private String visibility = "PUBLIC";

    public ImportRepositoryRequest() {
    }

    public ImportRepositoryRequest(String githubRepoId, String ownerName, String repoName, String defaultBranch, String cloneUrl, String webUrl, String visibility) {
        this.githubRepoId = githubRepoId;
        this.ownerName = ownerName;
        this.repoName = repoName;
        this.defaultBranch = defaultBranch != null ? defaultBranch : "main";
        this.cloneUrl = cloneUrl;
        this.webUrl = webUrl;
        this.visibility = visibility != null ? visibility : "PUBLIC";
    }

    public String getGithubRepoId() {
        return githubRepoId;
    }

    public void setGithubRepoId(String githubRepoId) {
        this.githubRepoId = githubRepoId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getCloneUrl() {
        return cloneUrl;
    }

    public void setCloneUrl(String cloneUrl) {
        this.cloneUrl = cloneUrl;
    }

    public String getWebUrl() {
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
