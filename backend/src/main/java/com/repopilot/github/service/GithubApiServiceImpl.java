package com.repopilot.github.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.dto.BranchDto;
import com.repopilot.github.dto.FileContentDto;
import com.repopilot.github.dto.FileTreeItemDto;
import com.repopilot.github.dto.GithubPullRequestDto;
import com.repopilot.github.dto.GithubRepoDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class GithubApiServiceImpl implements GithubApiService {

    private static final Logger logger = LoggerFactory.getLogger(GithubApiServiceImpl.class);
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${repopilot.github.client-id:placeholder}")
    private String clientId;

    @Value("${repopilot.github.client-secret:placeholder}")
    private String clientSecret;

    @Value("${repopilot.github.redirect-uri:http://localhost:3000/api/auth/callback/github}")
    private String redirectUri;

    public GithubApiServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String exchangeCodeForAccessToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        Map<String, String> body = new HashMap<>();
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("code", code);
        body.put("redirect_uri", redirectUri);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(GITHUB_OAUTH_TOKEN_URL, request, JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = response.getBody();
                if (json.has("error")) {
                    String errorDesc = json.has("error_description") ? json.get("error_description").asText() : json.get("error").asText();
                    throw new ApiException(HttpStatus.BAD_REQUEST, "GitHub OAuth error: " + errorDesc);
                }
                if (json.has("access_token")) {
                    return json.get("access_token").asText();
                }
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to retrieve access token from GitHub");
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            logger.error("GitHub OAuth token exchange HTTP error status={}", e.getStatusCode());
            throw new ApiException(HttpStatus.BAD_REQUEST, "GitHub OAuth token exchange failed");
        } catch (ResourceAccessException e) {
            logger.error("GitHub connection timeout during token exchange");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Timeout connecting to GitHub authentication service");
        }
    }

    @Override
    public Map<String, String> fetchAuthenticatedUser(String accessToken) {
        JsonNode node = executeGet("/user", accessToken, JsonNode.class);
        Map<String, String> userDetails = new HashMap<>();
        userDetails.put("id", node.has("id") ? node.get("id").asText() : "");
        userDetails.put("login", node.has("login") ? node.get("login").asText() : "");
        userDetails.put("avatarUrl", node.has("avatar_url") ? node.get("avatar_url").asText() : "");
        return userDetails;
    }

    @Override
    public List<GithubRepoDto> listUserRepositories(String accessToken) {
        JsonNode arrayNode = executeGet("/user/repos?per_page=100&sort=updated", accessToken, JsonNode.class);
        List<GithubRepoDto> repos = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String id = item.has("id") ? item.get("id").asText() : "";
                String name = item.has("name") ? item.get("name").asText() : "";
                String owner = item.has("owner") && item.get("owner").has("login") ? item.get("owner").get("login").asText() : "";
                String fullName = item.has("full_name") ? item.get("full_name").asText() : owner + "/" + name;
                String defaultBranch = item.has("default_branch") ? item.get("default_branch").asText() : "main";
                String cloneUrl = item.has("clone_url") ? item.get("clone_url").asText() : "";
                String htmlUrl = item.has("html_url") ? item.get("html_url").asText() : "";
                boolean isPrivate = item.has("private") && item.get("private").asBoolean();
                String description = item.has("description") && !item.get("description").isNull() ? item.get("description").asText() : "";

                repos.add(new GithubRepoDto(id, owner, name, fullName, defaultBranch, cloneUrl, htmlUrl, isPrivate, description));
            }
        }
        return repos;
    }

    @Override
    public GithubRepoDto fetchRepositoryMetadata(String accessToken, String owner, String repo) {
        JsonNode item = executeGet("/repos/" + owner + "/" + repo, accessToken, JsonNode.class);
        String id = item.has("id") ? item.get("id").asText() : "";
        String name = item.has("name") ? item.get("name").asText() : repo;
        String ownerName = item.has("owner") && item.get("owner").has("login") ? item.get("owner").get("login").asText() : owner;
        String fullName = item.has("full_name") ? item.get("full_name").asText() : ownerName + "/" + name;
        String defaultBranch = item.has("default_branch") ? item.get("default_branch").asText() : "main";
        String cloneUrl = item.has("clone_url") ? item.get("clone_url").asText() : "";
        String htmlUrl = item.has("html_url") ? item.get("html_url").asText() : "";
        boolean isPrivate = item.has("private") && item.get("private").asBoolean();
        String description = item.has("description") && !item.get("description").isNull() ? item.get("description").asText() : "";

        return new GithubRepoDto(id, ownerName, name, fullName, defaultBranch, cloneUrl, htmlUrl, isPrivate, description);
    }

    @Override
    public List<BranchDto> listRepositoryBranches(String accessToken, String owner, String repo) {
        JsonNode arrayNode = executeGet("/repos/" + owner + "/" + repo + "/branches?per_page=100", accessToken, JsonNode.class);
        List<BranchDto> branches = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String name = item.has("name") ? item.get("name").asText() : "";
                String sha = item.has("commit") && item.get("commit").has("sha") ? item.get("commit").get("sha").asText() : "";
                branches.add(new BranchDto(null, name, sha, false));
            }
        }
        return branches;
    }

    @Override
    public BranchDto fetchBranchDetails(String accessToken, String owner, String repo, String branch) {
        JsonNode item = executeGet("/repos/" + owner + "/" + repo + "/branches/" + branch, accessToken, JsonNode.class);
        String name = item.has("name") ? item.get("name").asText() : branch;
        String sha = item.has("commit") && item.get("commit").has("sha") ? item.get("commit").get("sha").asText() : "";
        return new BranchDto(null, name, sha, false);
    }

    @Override
    public List<FileTreeItemDto> fetchRepositoryFileTree(String accessToken, String owner, String repo, String branch) {
        String path = "/repos/" + owner + "/" + repo + "/git/trees/" + branch + "?recursive=1";
        JsonNode root = executeGet(path, accessToken, JsonNode.class);
        List<FileTreeItemDto> treeItems = new ArrayList<>();
        if (root != null && root.has("tree") && root.get("tree").isArray()) {
            for (JsonNode node : root.get("tree")) {
                String itemPath = node.has("path") ? node.get("path").asText() : "";
                String type = node.has("type") ? node.get("type").asText() : "blob";
                Long size = node.has("size") ? node.get("size").asLong() : 0L;
                String sha = node.has("sha") ? node.get("sha").asText() : "";

                treeItems.add(new FileTreeItemDto(itemPath, type, size, sha));
            }
        }
        return treeItems;
    }

    public static String encodePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String[] segments = path.split("/");
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append("/");
            }
            try {
                String encodedSegment = URLEncoder.encode(segments[i], StandardCharsets.UTF_8)
                        .replace("+", "%20")
                        .replace("*", "%2A")
                        .replace("%7E", "~");
                encoded.append(encodedSegment);
            } catch (Exception e) {
                encoded.append(segments[i]);
            }
        }
        return encoded.toString();
    }

    @Override
    public FileContentDto fetchFileContent(String accessToken, String owner, String repo, String branch, String filePath) {
        String encodedFilePath = encodePath(filePath);
        String encodedBranch = encodePath(branch);
        String path = "/repos/" + owner + "/" + repo + "/contents/" + encodedFilePath + "?ref=" + encodedBranch;

        JsonNode node = null;
        try {
            node = executeGet(path, accessToken, JsonNode.class);
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                logger.warn("GitHub API 404 Not Found for file path='{}', treating as unreadable file.", filePath);
                return new FileContentDto(filePath, branch, "", "utf-8", 0L, true);
            }
            throw e;
        }

        String pathName = node.has("path") ? node.get("path").asText() : filePath;
        Long size = node.has("size") ? node.get("size").asLong() : 0L;
        String encoding = node.has("encoding") ? node.get("encoding").asText() : "utf-8";
        String rawContent = node.has("content") ? node.get("content").asText() : "";

        String decodedContent = rawContent;
        boolean isBinary = false;

        if ("base64".equalsIgnoreCase(encoding) && !rawContent.isEmpty()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(rawContent.replaceAll("\\s+", ""));
                if (isBinaryBytes(bytes)) {
                    isBinary = true;
                    decodedContent = ""; // Do not construct text string from binary bytes
                } else {
                    decodedContent = new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                logger.warn("Failed to decode base64 file content for path={}", filePath);
            }
        }

        if (!isBinary && isBinaryContent(decodedContent)) {
            isBinary = true;
        }

        return new FileContentDto(pathName, branch, decodedContent, encoding, size, isBinary);
    }

    private boolean isBinaryBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        int checkLen = Math.min(bytes.length, 8192);
        for (int i = 0; i < checkLen; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isBinaryContent(String content) {
        if (content == null) return false;
        return content.indexOf('\u0000') >= 0;
    }

    private <T> T executeGet(String endpointPath, String accessToken, Class<T> responseType) {
        HttpHeaders headers = createAuthHeaders(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = GITHUB_API_BASE + endpointPath;

        try {
            ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            HttpHeaders responseHeaders = e.getResponseHeaders();
            String limitStr = (responseHeaders != null) ? responseHeaders.getFirst("X-RateLimit-Limit") : null;
            String remainingStr = (responseHeaders != null) ? responseHeaders.getFirst("X-RateLimit-Remaining") : null;
            String resetStr = (responseHeaders != null) ? responseHeaders.getFirst("X-RateLimit-Reset") : null;
            String retryAfterStr = (responseHeaders != null) ? responseHeaders.getFirst("Retry-After") : null;
            String responseBody = e.getResponseBodyAsString();

            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                logger.warn("GitHub API 401 Unauthorized for endpoint={}", endpointPath);
                throw new ApiException(HttpStatus.UNAUTHORIZED, "GITHUB_UNAUTHORIZED", "GitHub token is invalid or expired. Please reconnect your GitHub account.");
            } else if (e.getStatusCode() == HttpStatus.FORBIDDEN || e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                boolean isPrimaryExhausted = "0".equals(remainingStr) || (responseBody != null && responseBody.toLowerCase().contains("rate limit exceeded"));

                if (isPrimaryExhausted || resetStr != null) {
                    long resetEpochSec = System.currentTimeMillis() / 1000 + 3600;
                    if (resetStr != null) {
                        try {
                            resetEpochSec = Long.parseLong(resetStr);
                        } catch (NumberFormatException ignored) {}
                    }
                    long currentEpochSec = System.currentTimeMillis() / 1000;
                    long secondsRemaining = Math.max(0, resetEpochSec - currentEpochSec);
                    long minutesRemaining = (secondsRemaining + 59) / 60;
                    String formattedReset = java.time.Instant.ofEpochSecond(resetEpochSec).toString();

                    String userMessage = String.format(
                            "GitHub rate limit exhausted (%s/%s remaining). Resets at %s (in %d minutes). Please retry after reset time.",
                            (remainingStr != null ? remainingStr : "0"),
                            (limitStr != null ? limitStr : "5000"),
                            formattedReset,
                            minutesRemaining
                    );
                    logger.error("GitHub API primary rate limit exhausted for endpoint={}: {}", endpointPath, userMessage);
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GITHUB_RATE_LIMIT_EXHAUSTED", userMessage);
                }

                boolean isSecondaryRateLimit = (retryAfterStr != null) || (responseBody != null && responseBody.toLowerCase().contains("secondary rate limit"));
                if (isSecondaryRateLimit) {
                    long waitSeconds = 60;
                    if (retryAfterStr != null) {
                        try {
                            waitSeconds = Long.parseLong(retryAfterStr);
                        } catch (NumberFormatException ignored) {}
                    }
                    String userMessage = String.format(
                            "GitHub secondary rate limit triggered (abuse detection). Please wait %d seconds before retrying.", waitSeconds
                    );
                    logger.warn("GitHub API secondary rate limit triggered for endpoint={}: {}", endpointPath, userMessage);
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GITHUB_SECONDARY_RATE_LIMIT", userMessage);
                }

                logger.warn("GitHub API 403 Forbidden for endpoint={}", endpointPath);
                throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_FORBIDDEN", "GitHub access forbidden. Check repository permissions or token scopes.");
            } else if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.warn("GitHub API 404 Not Found for endpoint={}", endpointPath);
                throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_NOT_FOUND", "GitHub repository or file path not found.");
            } else {
                logger.error("GitHub API HTTP error status={} endpoint={}", e.getStatusCode(), endpointPath);
                throw new ApiException(HttpStatus.valueOf(e.getStatusCode().value()), "GITHUB_API_ERROR", "GitHub API error: " + e.getResponseBodyAsString());
            }
        } catch (HttpServerErrorException e) {
            logger.error("GitHub API server error status={} endpoint={}", e.getStatusCode(), endpointPath);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub API temporary failure");
        } catch (ResourceAccessException e) {
            logger.error("GitHub API timeout or connection failure for endpoint={}", endpointPath);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Timeout connecting to GitHub API");
        }
    }

    @Override
    public GithubPullRequestDto createPullRequest(String accessToken, String owner, String repo, String head, String base, String title, String body) {
        String path = String.format("/repos/%s/%s/pulls", encodePath(owner), encodePath(repo));
        HttpHeaders headers = createAuthHeaders(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("head", head);
        payload.put("base", base);

        HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(payload, headers);
        String url = GITHUB_API_BASE + path;

        try {
            ResponseEntity<GithubPullRequestDto> response = restTemplate.postForEntity(url, requestEntity, GithubPullRequestDto.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FAILED_TO_CREATE_PR", "GitHub API returned empty response for PR creation");
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            logger.error("GitHub API HTTP error creating PR for owner={} repo={}: status={}", owner, repo, e.getStatusCode());
            throw new ApiException(HttpStatus.valueOf(e.getStatusCode().value()), "GITHUB_API_ERROR", "GitHub PR creation error: " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            logger.error("GitHub API timeout or connection failure during PR creation for owner={} repo={}", owner, repo);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TIMEOUT_CONNECTING_TO_GITHUB", "Timeout connecting to GitHub API during PR creation");
        }
    }

    @Override
    public List<GithubPullRequestDto> findPullRequestsByHead(String accessToken, String owner, String repo, String head, String base) {
        String headQuery = head.contains(":") ? head : owner + ":" + head;
        String path = String.format("/repos/%s/%s/pulls?head=%s&state=all", encodePath(owner), encodePath(repo), encodePath(headQuery));

        try {
            GithubPullRequestDto[] prs = executeGet(path, accessToken, GithubPullRequestDto[].class);
            if (prs == null || prs.length == 0) {
                String fallbackPath = String.format("/repos/%s/%s/pulls?head=%s&state=all", encodePath(owner), encodePath(repo), encodePath(head));
                prs = executeGet(fallbackPath, accessToken, GithubPullRequestDto[].class);
            }
            return prs != null ? Arrays.asList(prs) : Collections.emptyList();
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return Collections.emptyList();
            }
            throw e;
        }
    }

    private HttpHeaders createAuthHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null && !accessToken.isEmpty()) {
            headers.setBearerAuth(accessToken);
        }
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "RepoPilot-Application");
        return headers;
    }
}
