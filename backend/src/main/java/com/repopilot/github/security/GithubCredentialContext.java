package com.repopilot.github.security;

import java.time.Instant;
import java.util.Arrays;

public class GithubCredentialContext implements AutoCloseable {

    private char[] tokenChars;
    private final String username;
    private final Instant createdAt;
    private boolean wiped = false;

    public GithubCredentialContext(char[] tokenChars, String username) {
        if (tokenChars == null || tokenChars.length == 0) {
            throw new IllegalArgumentException("Token characters cannot be null or empty");
        }
        this.tokenChars = Arrays.copyOf(tokenChars, tokenChars.length);
        this.username = (username != null && !username.isBlank()) ? username : "x-access-token";
        this.createdAt = Instant.now();
    }

    public GithubCredentialContext(String tokenString, String username) {
        this(tokenString != null ? tokenString.toCharArray() : null, username);
    }

    public synchronized String getToken() {
        checkWiped();
        return new String(tokenChars);
    }

    public synchronized char[] getTokenChars() {
        checkWiped();
        return Arrays.copyOf(tokenChars, tokenChars.length);
    }

    public String getUsername() {
        return username;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized boolean isWiped() {
        return wiped;
    }

    public synchronized void wipe() {
        if (!wiped && tokenChars != null) {
            Arrays.fill(tokenChars, '\0');
            wiped = true;
        }
    }

    private void checkWiped() {
        if (wiped) {
            throw new IllegalStateException("GithubCredentialContext has been wiped and closed");
        }
    }

    @Override
    public void close() {
        wipe();
    }

    @Override
    public String toString() {
        return "GithubCredentialContext[username=" + username + ", wiped=" + wiped + "]";
    }
}
