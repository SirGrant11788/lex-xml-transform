package com.lexisnexis.transform.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Normalized record emitted by the XSLT transform and persisted under
 * {@code out/valid/<content_id>.json}.
 *
 * <p>Mirrors the JSON target in the assignment brief; fields are intentionally
 * a superset-friendly shape so future schema evolution does not break clients.</p>
 */
public record NormalizedJudgment(
        @JsonProperty("content_id") String contentId,
        String title,
        String court,
        String jurisdiction,
        @JsonProperty("decision_date") LocalDate decisionDate,
        List<Citation> citations,
        List<Party> parties,
        List<Paragraph> paragraphs,
        @JsonProperty("full_text") String fullText
) {
    public record Citation(String type, String value) {}

    public record Party(String role, String name) {}

    public record Paragraph(String id, String section, String text) {}
}
