package com.devvault.discovery.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GitInfoReaderTest {

    private final GitInfoReader reader = new GitInfoReader();

    @Test
    void deberiaLeerRamaYCommitDesdeRefSuelta(@TempDir Path projectDir) throws IOException {
        Path gitDir = projectDir.resolve(".git");
        Files.createDirectories(gitDir.resolve("refs/heads"));
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(gitDir.resolve("refs/heads/main"), "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678\n");

        Optional<GitInfoReader.GitInfo> result = reader.read(projectDir);

        assertThat(result).isPresent();
        assertThat(result.get().branch()).isEqualTo("main");
        assertThat(result.get().shortCommitHash()).isEqualTo("a1b2c3d");
    }

    @Test
    void deberiaRetornarVacioSiNoHayCarpetaGit(@TempDir Path projectDir) {
        Optional<GitInfoReader.GitInfo> result = reader.read(projectDir);

        assertThat(result).isEmpty();
    }

    @Test
    void deberiaManejarHeadDetached(@TempDir Path projectDir) throws IOException {
        Path gitDir = projectDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678\n");

        Optional<GitInfoReader.GitInfo> result = reader.read(projectDir);

        assertThat(result).isPresent();
        assertThat(result.get().branch()).isEqualTo("(detached)");
    }
}