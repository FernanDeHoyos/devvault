package com.devvault.workspace.api;

import com.devvault.shared.api.exception.ApiException;
import com.devvault.workspace.application.dto.DirectoryEntryResponse;
import com.devvault.workspace.application.dto.DirectoryListingResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/v1/workspaces/directories")
public class WorkspaceBrowserController {

    @GetMapping
    public DirectoryListingResponse listDirectories(@RequestParam(required = false) String path) {
        Path current;
        if (path == null || path.isBlank()) {
            try {
                current = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                current = null;
            }
            if (current == null || !Files.isDirectory(current) || !Files.isReadable(current)) {
                List<DirectoryEntryResponse> roots = StreamSupport.stream(
                                java.nio.file.FileSystems.getDefault().getRootDirectories().spliterator(), false)
                        .map(root -> new DirectoryEntryResponse(root.toString(), root.toAbsolutePath().normalize().toString()))
                        .toList();
                return new DirectoryListingResponse(null, null, roots);
            }
        } else {
            try {
                current = Path.of(path).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid directory path.");
            }
        }
        if (!Files.isDirectory(current) || !Files.isReadable(current)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Directory does not exist or cannot be read: " + current);
        }

        try (var children = Files.list(current)) {
            List<DirectoryEntryResponse> directories = children
                    .filter(Files::isDirectory)
                    .map(directory -> new DirectoryEntryResponse(
                            directory.getFileName() == null ? directory.toString() : directory.getFileName().toString(),
                            directory.toAbsolutePath().normalize().toString()))
                    .sorted(Comparator.comparing(DirectoryEntryResponse::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            Path parent = current.getParent();
            return new DirectoryListingResponse(current.toString(), parent == null ? null : parent.toString(), directories);
        } catch (IOException | SecurityException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not list directory: " + current);
        }
    }
}
