package com.devvault.discovery.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devvault.discovery.application.dto.GitBranchResponse;
import com.devvault.discovery.application.dto.GitCommitResponse;
import com.devvault.discovery.application.dto.GitFetchEventResponse;
import com.devvault.discovery.application.dto.GitRepositoryResponse;
import com.devvault.discovery.domain.GitFetchEvent;
import com.devvault.discovery.domain.Project;
import com.devvault.discovery.infraestructure.GitFetchEventRepository;
import com.devvault.discovery.infraestructure.ProjectRepository;
import com.devvault.shared.api.exception.ApiException;

@Service
public class GitTrackingService {
    private static final int MAX_OUTPUT_BYTES = 2_000_000;
    private static final Pattern TRACKING_COUNTS = Pattern.compile("ahead (\\d+).*behind (\\d+)|behind (\\d+).*ahead (\\d+)");
    private static final Pattern BEHIND_ONLY = Pattern.compile("behind (\\d+)");
    private static final Pattern AHEAD_ONLY = Pattern.compile("ahead (\\d+)");
    private static final Pattern COMMIT_RECORD = Pattern.compile("(?s)(.*?)\\u001f(.*?)\\u001f(.*?)\\u001f(.*?)\\u001f(.*?)\\u001f(.*?)\\u001f(.*?)\\u001e");

    private final ProjectRepository projectRepository;
    private final GitFetchEventRepository fetchEventRepository;

    public GitTrackingService(ProjectRepository projectRepository, GitFetchEventRepository fetchEventRepository) {
        this.projectRepository = projectRepository;
        this.fetchEventRepository = fetchEventRepository;
    }

    @Transactional(readOnly = true)
    public GitRepositoryResponse inspect(UUID projectId) {
        Project project = findProject(projectId);
        Path root = Path.of(project.getPath()).toAbsolutePath().normalize();
        if (!root.toFile().isDirectory()) return emptyRepository();
        try {
            run(root, List.of("rev-parse", "--show-toplevel"), 10);
        } catch (ApiException exception) {
            if (exception.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY) return emptyRepository();
            throw exception;
        }

        String status = run(root, List.of("status", "--porcelain=v1", "--branch", "--untracked-files=normal"), 15);
        String head;
        try {
            head = run(root, List.of("rev-parse", "--short=12", "HEAD"), 10);
        } catch (ApiException noCommitYet) {
            head = null;
        }
        StatusSummary summary = parseStatus(status, head);
        List<GitBranchResponse> branches = parseBranches(run(root, List.of("for-each-ref",
                "--format=%(refname)%09%(objectname:short)%09%(upstream:short)%09%(HEAD)",
                "refs/heads", "refs/remotes"), 15));
        List<GitCommitResponse> commits = parseCommits(run(root, List.of("log", "--all", "--topo-order",
                "--date=iso-strict", "--format=%H%x1f%h%x1f%an%x1f%ad%x1f%s%x1f%P%x1f%D%x1e", "-n", "50"), 20));
        List<GitFetchEventResponse> history = fetchEventRepository.findTop10ByProjectIdOrderByFetchedAtDesc(projectId)
                .stream().map(event -> new GitFetchEventResponse(event.getFetchedAt(), event.getStatus(), event.getRemotes())).toList();
        Instant lastFetchAt = fetchEventRepository.findFirstByProjectIdAndStatusOrderByFetchedAtDesc(projectId, "SUCCESS")
                .map(event -> event.getFetchedAt()).orElse(null);

        return new GitRepositoryResponse(true, summary.branch(), summary.commit(), summary.upstream(), summary.ahead(),
                summary.behind(), summary.staged(), summary.modified(), summary.untracked(), summary.conflicted(),
                lastFetchAt, branches, commits, history);
    }

    private GitRepositoryResponse emptyRepository() {
        return new GitRepositoryResponse(false, null, null, null, 0, 0, 0, 0, 0, 0, null,
                List.of(), List.of(), List.of());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public GitRepositoryResponse fetch(UUID projectId) {
        Project project = findProject(projectId);
        Path root = Path.of(project.getPath()).toAbsolutePath().normalize();
        requireRepository(root);
        String remotes = run(root, List.of("remote"), 10).lines().filter(line -> !line.isBlank())
                .reduce((left, right) -> left + ", " + right).orElse("sin remotos configurados");
        if ("sin remotos configurados".equals(remotes)) {
            fetchEventRepository.save(new GitFetchEvent(projectId, "FAILED", remotes));
            throw new ApiException(HttpStatus.CONFLICT, "El repositorio no tiene remotos configurados.");
        }
        try {
            run(root, List.of("fetch", "--all", "--prune"), 120);
            fetchEventRepository.save(new GitFetchEvent(projectId, "SUCCESS", remotes));
        } catch (ApiException exception) {
            fetchEventRepository.save(new GitFetchEvent(projectId, "FAILED", remotes));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Fetch falló. Revisa la conexión y las credenciales configuradas en Git.");
        }
        return inspect(projectId);
    }

    private Project findProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Proyecto no encontrado: " + projectId));
    }

    private void requireRepository(Path root) {
        if (!root.toFile().isDirectory()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "La carpeta del proyecto no está disponible.");
        }
        run(root, List.of("rev-parse", "--show-toplevel"), 10);
    }

    private String run(Path root, List<String> args, int timeoutSeconds) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(args);
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");
            processBuilder.environment().put("GCM_INTERACTIVE", "never");
            process = processBuilder.start();
            process.getOutputStream().close();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No se encontró Git o no se pudo iniciar el proceso.");
        }

        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
            try (var stream = process.getInputStream()) {
                return stream.readNBytes(MAX_OUTPUT_BYTES + 1);
            } catch (IOException exception) {
                return new byte[0];
            }
        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Git excedió el tiempo permitido para esta operación.");
            }
            byte[] bytes = outputFuture.get(5, TimeUnit.SECONDS);
            String output = new String(bytes, 0, Math.min(bytes.length, MAX_OUTPUT_BYTES), StandardCharsets.UTF_8).trim();
            if (bytes.length > MAX_OUTPUT_BYTES) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "La respuesta de Git excede el límite permitido.");
            }
            if (process.exitValue() != 0) {
                HttpStatus status = args.get(0).equals("rev-parse") ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
                String message = output.isBlank() ? "Git no pudo completar la operación." : output;
                throw new ApiException(status, message);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "La operación Git fue interrumpida.");
        } catch (TimeoutException exception) {
            process.destroyForcibly();
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "No se pudo leer la respuesta de Git a tiempo.");
        } catch (java.util.concurrent.ExecutionException exception) {
            process.destroyForcibly();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "No se pudo leer la respuesta del proceso Git.");
        }
    }

    private StatusSummary parseStatus(String output, String head) {
        String branch = null;
        String upstream = null;
        String commit = head;
        int ahead = 0, behind = 0, staged = 0, modified = 0, untracked = 0, conflicted = 0;
        for (String line : output.split("\\R")) {
            if (line.startsWith("## ")) {
                String reference = line.substring(3);
                int tracking = reference.indexOf("...");
                if (tracking >= 0) {
                    branch = reference.substring(0, tracking);
                    String tail = reference.substring(tracking + 3);
                    int bracket = tail.indexOf(' ');
                    upstream = bracket < 0 ? tail : tail.substring(0, bracket);
                    Matcher both = TRACKING_COUNTS.matcher(tail);
                    if (both.find()) {
                        if (both.group(1) != null) { ahead = Integer.parseInt(both.group(1)); behind = Integer.parseInt(both.group(2)); }
                        else { behind = Integer.parseInt(both.group(3)); ahead = Integer.parseInt(both.group(4)); }
                    } else {
                        Matcher aheadOnly = AHEAD_ONLY.matcher(tail);
                        Matcher behindOnly = BEHIND_ONLY.matcher(tail);
                        if (aheadOnly.find()) ahead = Integer.parseInt(aheadOnly.group(1));
                        if (behindOnly.find()) behind = Integer.parseInt(behindOnly.group(1));
                    }
                } else if (reference.startsWith("No commits yet on ")) branch = reference.substring("No commits yet on ".length());
                else if (reference.equals("HEAD (no branch)")) branch = "(detached)";
                else branch = reference;
                continue;
            }
            if (line.length() < 2) continue;
            char index = line.charAt(0), worktree = line.charAt(1);
            if (index == '?' && worktree == '?') untracked++;
            else if ("U".indexOf(index) >= 0 || "U".indexOf(worktree) >= 0 || (index == 'A' && worktree == 'A')
                    || (index == 'D' && worktree == 'D')) conflicted++;
            else {
                if (index != ' ') staged++;
                if (worktree != ' ') modified++;
            }
        }
        return new StatusSummary(branch, commit, upstream, ahead, behind, staged, modified, untracked, conflicted);
    }

    private List<GitBranchResponse> parseBranches(String output) {
        return output.lines().filter(line -> !line.isBlank()).map(line -> {
            String[] fields = line.split("\\t", -1);
            String reference = fields[0];
            boolean remote = reference.startsWith("refs/remotes/");
            String name = reference.startsWith("refs/heads/") ? reference.substring("refs/heads/".length())
                    : reference.startsWith("refs/remotes/") ? reference.substring("refs/remotes/".length()) : reference;
            boolean current = fields.length > 3 && "*".equals(fields[3]);
            if (name.endsWith("/HEAD")) return null;
            return new GitBranchResponse(name, fields.length > 1 ? fields[1] : "", fields.length > 2 ? fields[2] : "",
                    current, remote);
        }).filter(java.util.Objects::nonNull).toList();
    }

    private List<GitCommitResponse> parseCommits(String output) {
        List<GitCommitResponse> commits = new ArrayList<>();
        Matcher matcher = COMMIT_RECORD.matcher(output + "\u001e");
        while (matcher.find()) {
            try {
                Instant committedAt = OffsetDateTime.parse(matcher.group(4)).toInstant();
                List<String> parents = matcher.group(6).isBlank() ? List.of() : Arrays.asList(matcher.group(6).split(" "));
                commits.add(new GitCommitResponse(matcher.group(1), matcher.group(2), matcher.group(3), committedAt,
                        matcher.group(5), parents, matcher.group(7)));
            } catch (RuntimeException ignored) {
                // Skip malformed commit records while keeping the rest of the graph.
            }
        }
        return commits;
    }

    private record StatusSummary(String branch, String commit, String upstream, int ahead, int behind,
            int staged, int modified, int untracked, int conflicted) {}
}
