package com.devvault.runtime.api;

import com.devvault.runtime.application.WorkspaceGroupStartup;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{id}/group-startup")
public class WorkspaceGroupStartupController {
    private final WorkspaceGroupStartup startup;

    public WorkspaceGroupStartupController(WorkspaceGroupStartup startup) {
        this.startup = startup;
    }

    public record Request(List<UUID> projectIds) {}

    @PostMapping
    public ResponseEntity<WorkspaceGroupStartup.Report> start(
            @PathVariable("id") UUID id, @RequestBody Request request) {
        return ResponseEntity.accepted().body(startup.start(id, request.projectIds()));
    }

    @GetMapping
    public ResponseEntity<WorkspaceGroupStartup.Report> latest(@PathVariable("id") UUID id) {
        var report = startup.latest(id);
        return report == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(report);
    }
}
