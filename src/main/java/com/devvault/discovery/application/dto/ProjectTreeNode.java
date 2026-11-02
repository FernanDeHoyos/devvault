package com.devvault.discovery.application.dto;

import java.util.List;

public record ProjectTreeNode(
        String name,
        String path,
        boolean directory,
        List<ProjectTreeNode> children,
        boolean truncated) {
}
