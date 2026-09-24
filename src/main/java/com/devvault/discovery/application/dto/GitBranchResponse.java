package com.devvault.discovery.application.dto;

public record GitBranchResponse(String name, String commit, String upstream, boolean current, boolean remote) {}
