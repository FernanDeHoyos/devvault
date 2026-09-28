package com.devvault.auth.dto;

import java.time.Instant;

public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, String username) {}
