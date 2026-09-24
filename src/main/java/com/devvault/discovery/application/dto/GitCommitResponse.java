package com.devvault.discovery.application.dto;

import java.time.Instant;
import java.util.List;

public record GitCommitResponse(String hash, String shortHash, String author, Instant committedAt,
        String subject, List<String> parents, String decorations) {}
