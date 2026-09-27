package com.repopilot.github.dto;

public class GithubAuthorizeResponse {

    private String authorizeUrl;
    private String state;

    public GithubAuthorizeResponse() {
    }

    public GithubAuthorizeResponse(String authorizeUrl, String state) {
        this.authorizeUrl = authorizeUrl;
        this.state = state;
    }

    public String getAuthorizeUrl() {
        return authorizeUrl;
    }

    public void setAuthorizeUrl(String authorizeUrl) {
        this.authorizeUrl = authorizeUrl;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
