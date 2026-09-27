package com.repopilot.github.service;

import com.repopilot.github.dto.BranchDto;
import com.repopilot.github.dto.FileContentDto;
import com.repopilot.github.dto.FileTreeItemDto;
import com.repopilot.github.dto.GithubRepoDto;

import java.util.List;
import java.util.Map;

public interface GithubApiService {

    String exchangeCodeForAccessToken(String code);

    Map<String, String> fetchAuthenticatedUser(String accessToken);

    List<GithubRepoDto> listUserRepositories(String accessToken);

    GithubRepoDto fetchRepositoryMetadata(String accessToken, String owner, String repo);

    List<BranchDto> listRepositoryBranches(String accessToken, String owner, String repo);

    BranchDto fetchBranchDetails(String accessToken, String owner, String repo, String branch);

    List<FileTreeItemDto> fetchRepositoryFileTree(String accessToken, String owner, String repo, String branch);

    FileContentDto fetchFileContent(String accessToken, String owner, String repo, String branch, String filePath);

    com.repopilot.github.dto.GithubPullRequestDto createPullRequest(String accessToken, String owner, String repo, String head, String base, String title, String body);

    List<com.repopilot.github.dto.GithubPullRequestDto> findPullRequestsByHead(String accessToken, String owner, String repo, String head, String base);
}
