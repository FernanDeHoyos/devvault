package com.devvault.discovery.application.dto;

import java.time.Instant;
import java.util.List;

public record GitRepositoryResponse(
        boolean repository,
        String branch,
        String commit,
        String upstream,
        int ahead,
        int behind,
        int staged,
        int modified,
        int untracked,
        int conflicted,
        Instant lastFetchAt,
        List<GitBranchResponse> branches,
        List<GitCommitResponse> commits,
        List<GitFetchEventResponse> fetchHistory) {}
