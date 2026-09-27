package com.repopilot.github.dto;

import java.time.Instant;

public class GithubStatusDto {

    private boolean connected;
    private String githubUsername;
    private Instant connectedAt;

    public GithubStatusDto() {
    }

    public GithubStatusDto(boolean connected, String githubUsername, Instant connectedAt) {
        this.connected = connected;
        this.githubUsername = githubUsername;
        this.connectedAt = connectedAt;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public String getGithubUsername() {
        return githubUsername;
    }

    public void setGithubUsername(String githubUsername) {
        this.githubUsername = githubUsername;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(Instant connectedAt) {
        this.connectedAt = connectedAt;
    }
}
