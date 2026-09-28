package com.devvault.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "devvault.auth")
public record AuthProperties(@Min(5) long tokenDurationMinutes) {}
