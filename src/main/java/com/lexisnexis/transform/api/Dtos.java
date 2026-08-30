package com.lexisnexis.transform.api;

import com.lexisnexis.transform.model.DocumentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Wire DTOs for the REST API. Kept separate from internal records so the
 * public contract can evolve independently of the domain model.
 */
public final class Dtos {
    private Dtos() {}

    /** Response after submitting a single document. */
    public record IngestResponse(
            @NotNull DocumentStatus status,
            @NotBlank String contentId,
            String message,
            String artifactPath,
            String sha256,
            List<String> diagnostics
    ) {}

    /** Request body for the batch endpoint when a server-side folder is named. */
    public record BatchFolderRequest(@NotBlank String folderPath) {}

    /** Aggregate response for batch submission. */
    public record BatchResponse(
            int total,
            int published,
            int invalid,
            int duplicate,
            int failed,
            List<String> files
    ) {}

    /** Response for a status / retrieval query. */
    public record StatusResponse(
            String contentId,
            DocumentStatus status,
            String artifactPath,
            String sha256
    ) {}
}
