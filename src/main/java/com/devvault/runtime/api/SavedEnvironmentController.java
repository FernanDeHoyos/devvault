package com.devvault.runtime.api;

import com.devvault.runtime.application.SavedEnvironmentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspace}/environments")
public class SavedEnvironmentController {
    private final SavedEnvironmentService service;
    public SavedEnvironmentController(SavedEnvironmentService service) { this.service = service; }
    @GetMapping public List<SavedEnvironmentService.Environment> list(@PathVariable("workspace") UUID workspace) { return service.list(workspace); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public SavedEnvironmentService.Environment create(@PathVariable("workspace") UUID workspace, @RequestBody SavedEnvironmentService.Definition definition) { return service.save(workspace, null, definition); }
    @PutMapping("/{id}") public SavedEnvironmentService.Environment update(@PathVariable("workspace") UUID workspace, @PathVariable("id") UUID id, @RequestBody SavedEnvironmentService.Definition definition) { return service.save(workspace, id, definition); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("workspace") UUID workspace, @PathVariable("id") UUID id) { service.delete(workspace, id); }
    @GetMapping("/{id}/runs") public List<SavedEnvironmentService.Run> history(@PathVariable("workspace") UUID workspace, @PathVariable("id") UUID id) { return service.history(workspace, id); }
    public record Operation(String operation) {}
    @PostMapping("/{id}/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public SavedEnvironmentService.Run execute(@PathVariable("workspace") UUID workspace, @PathVariable("id") UUID id, @RequestBody Operation request) { return service.execute(workspace, id, request.operation()); }
}
