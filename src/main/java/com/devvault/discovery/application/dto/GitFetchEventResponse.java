package com.devvault.discovery.application.dto;

import java.time.Instant;

public record GitFetchEventResponse(Instant fetchedAt, String status, String remotes) {}
