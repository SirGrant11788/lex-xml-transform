package com.lexisnexis.transform.ingest;

import java.util.List;

/**
 * Aggregate result of a batch folder submission.
 */
public record BatchOutcome(
        int total,
        int published,
        int invalid,
        int duplicate,
        int failed,
        List<String> files
) {}
