package com.lexisnexis.transform.model;

/**
 * Status of a single document through the pipeline.
 */
public enum DocumentStatus {
    RECEIVED,
    VALID,
    INVALID,
    PUBLISHED,
    DUPLICATE
}
