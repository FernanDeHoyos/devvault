package com.devvault.editor.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.shared.api.exception.ApiException;

class OpenProjectInEditorUseCaseTest {

    @Test
    void rejectsEvidencePathsOutsideTheProject(@TempDir Path projectPath) {
        UUID projectId = UUID.randomUUID();
        ProjectLookupService lookupService = mock(ProjectLookupService.class);
        when(lookupService.findById(projectId)).thenReturn(Optional.of(
                new ProjectLookupService.ProjectSummary(projectId, "sample", projectPath.toString())));
        OpenProjectInEditorUseCase useCase = new OpenProjectInEditorUseCase(lookupService, null, null);

        assertThrows(ApiException.class, () -> useCase.execute(projectId, null, "..\\outside.txt"));
    }
}
