package com.repopilot.github.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.github.dto.GithubAuthorizeResponse;
import com.repopilot.github.dto.GithubRepoDto;
import com.repopilot.github.dto.GithubStatusDto;
import com.repopilot.github.service.GithubService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/github")
public class GithubController {

    private final GithubService githubService;

    public GithubController(GithubService githubService) {
        this.githubService = githubService;
    }

    @GetMapping("/authorize")
    public ResponseEntity<GithubAuthorizeResponse> getAuthorizeUrl(@AuthenticationPrincipal UserPrincipal principal) {
        GithubAuthorizeResponse response = githubService.getAuthorizationUrl(principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/callback")
    public ResponseEntity<GithubStatusDto> handleCallbackGet(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        GithubStatusDto status = githubService.handleOAuthCallback(principal.getUser(), code, state);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/callback")
    public ResponseEntity<GithubStatusDto> handleCallbackPost(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        String code = body.get("code");
        String state = body.get("state");
        GithubStatusDto status = githubService.handleOAuthCallback(principal.getUser(), code, state);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status")
    public ResponseEntity<GithubStatusDto> getStatus(@AuthenticationPrincipal UserPrincipal principal) {
        GithubStatusDto status = githubService.getGithubStatus(principal.getUser());
        return ResponseEntity.ok(status);
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, String>> disconnect(@AuthenticationPrincipal UserPrincipal principal) {
        githubService.disconnectGithub(principal.getUser());
        return ResponseEntity.ok(Collections.singletonMap("message", "GitHub account disconnected successfully"));
    }

    @GetMapping("/repositories")
    public ResponseEntity<List<GithubRepoDto>> getUserRepositories(@AuthenticationPrincipal UserPrincipal principal) {
        List<GithubRepoDto> repos = githubService.getUserRepositories(principal.getUser());
        return ResponseEntity.ok(repos);
    }
}
