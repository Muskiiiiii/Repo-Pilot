package com.repopilot.github.dto;

public class GithubRepoDto {

    private String id;
    private String owner;
    private String name;
    private String fullName;
    private String defaultBranch;
    private String cloneUrl;
    private String htmlUrl;
    private boolean isPrivate;
    private String description;

    public GithubRepoDto() {
    }

    public GithubRepoDto(String id, String owner, String name, String fullName, String defaultBranch, String cloneUrl, String htmlUrl, boolean isPrivate, String description) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.fullName = fullName;
        this.defaultBranch = defaultBranch;
        this.cloneUrl = cloneUrl;
        this.htmlUrl = htmlUrl;
        this.isPrivate = isPrivate;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
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

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
