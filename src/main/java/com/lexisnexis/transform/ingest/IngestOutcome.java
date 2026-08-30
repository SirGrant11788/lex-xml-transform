package com.lexisnexis.transform.ingest;

import com.lexisnexis.transform.model.DocumentStatus;

import java.util.List;

/**
 * Outcome of processing one document.
 */
public record IngestOutcome(
        DocumentStatus status,
        String contentId,
        String message,
        String artifactPath,
        String sha256,
        List<String> diagnostics
) {
    public static IngestOutcome published(String contentId, String path, String sha) {
        return new IngestOutcome(DocumentStatus.PUBLISHED, contentId, "Published", path, sha, List.of());
    }

    public static IngestOutcome duplicate(String contentId, String existingSha) {
        return new IngestOutcome(DocumentStatus.DUPLICATE, contentId,
                "Duplicate — identical artifact already published", null, existingSha, List.of());
    }

    public static IngestOutcome invalid(String contentId, List<String> diagnostics) {
        return new IngestOutcome(DocumentStatus.INVALID, contentId,
                "Invalid — failed XSD validation", null, null, diagnostics);
    }

    public static IngestOutcome failed(String contentId, String message) {
        return new IngestOutcome(DocumentStatus.RECEIVED, contentId,
                message, null, null, List.of());
    }
}
