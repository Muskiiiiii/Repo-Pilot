package com.repopilot.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GithubPullRequestDto {

    private Long id;
    private Long number;
    @JsonProperty("html_url")
    private String htmlUrl;
    private String state;
    private String title;
    private String body;
    private HeadBaseRef head;
    private HeadBaseRef base;

    public GithubPullRequestDto() {
    }

    public GithubPullRequestDto(Long id, Long number, String htmlUrl, String state, String title, String body, HeadBaseRef head, HeadBaseRef base) {
        this.id = id;
        this.number = number;
        this.htmlUrl = htmlUrl;
        this.state = state;
        this.title = title;
        this.body = body;
        this.head = head;
        this.base = base;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getNumber() {
        return number;
    }

    public void setNumber(Long number) {
        this.number = number;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public HeadBaseRef getHead() {
        return head;
    }

    public void setHead(HeadBaseRef head) {
        this.head = head;
    }

    public HeadBaseRef getBase() {
        return base;
    }

    public void setBase(HeadBaseRef base) {
        this.base = base;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HeadBaseRef {
        private String ref;
        private String sha;
        private RepoRef repo;

        public HeadBaseRef() {
        }

        public HeadBaseRef(String ref, String sha, RepoRef repo) {
            this.ref = ref;
            this.sha = sha;
            this.repo = repo;
        }

        public String getRef() {
            return ref;
        }

        public void setRef(String ref) {
            this.ref = ref;
        }

        public String getSha() {
            return sha;
        }

        public void setSha(String sha) {
            this.sha = sha;
        }

        public RepoRef getRepo() {
            return repo;
        }

        public void setRepo(RepoRef repo) {
            this.repo = repo;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepoRef {
        private Long id;
        private String name;
        private OwnerRef owner;

        public RepoRef() {
        }

        public RepoRef(Long id, String name, OwnerRef owner) {
            this.id = id;
            this.name = name;
            this.owner = owner;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public OwnerRef getOwner() {
            return owner;
        }

        public void setOwner(OwnerRef owner) {
            this.owner = owner;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OwnerRef {
        private String login;

        public OwnerRef() {
        }

        public OwnerRef(String login) {
            this.login = login;
        }

        public String getLogin() {
            return login;
        }

        public void setLogin(String login) {
            this.login = login;
        }
    }
}
