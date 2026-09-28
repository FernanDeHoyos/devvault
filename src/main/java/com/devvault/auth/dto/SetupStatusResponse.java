package com.devvault.auth.dto;

public record SetupStatusResponse(boolean setupRequired, boolean recoveryConfigured) {}
