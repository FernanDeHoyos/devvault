package com.devvault.discovery.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.discovery.application.GitTrackingService;
import com.devvault.discovery.application.dto.GitRepositoryResponse;

@RestController
@RequestMapping("/api/v1/projects/{id}/git")
public class GitTrackingController {
    private final GitTrackingService gitTrackingService;

    public GitTrackingController(GitTrackingService gitTrackingService) {
        this.gitTrackingService = gitTrackingService;
    }

    @GetMapping
    public GitRepositoryResponse inspect(@PathVariable UUID id) {
        return gitTrackingService.inspect(id);
    }

    @PostMapping("/fetch")
    public GitRepositoryResponse fetch(@PathVariable UUID id) {
        return gitTrackingService.fetch(id);
    }
}
