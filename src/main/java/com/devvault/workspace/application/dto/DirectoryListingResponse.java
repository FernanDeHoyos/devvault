package com.devvault.workspace.application.dto;

import java.util.List;

public record DirectoryListingResponse(String currentPath, String parentPath,
                                       List<DirectoryEntryResponse> directories) { }
