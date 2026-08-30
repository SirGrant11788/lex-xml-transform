package com.lexisnexis.transform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Externalized configuration for the ingest pipeline.
 *
 * <p>All values are environment-overridable via {@code LEX_INGEST_*} env vars
 * (Spring Boot relaxed binding) so the same image can be promoted across
 * dev/stage/prod without rebuilding.</p>
 */
@Validated
@ConfigurationProperties(prefix = "lex.ingest")
public record LexIngestProperties(
        @NotNull Path xsdPath,
        @NotNull Path xsltPath,
        @NotBlank String outputDir,
        @Min(1) int batchConcurrency,
        @Min(1024) int maxXmlBytes
) {
    public LexIngestProperties {
        if (xsdPath == null) xsdPath = Paths.get("schemas/judgment.xsd");
        if (xsltPath == null) xsltPath = Paths.get("xslt/judgment-to-json.xsl");
        if (outputDir == null || outputDir.isBlank()) outputDir = "./out";
        if (batchConcurrency < 1) batchConcurrency = 4;
        if (maxXmlBytes < 1024) maxXmlBytes = 25 * 1024 * 1024; // 25 MiB default
    }

    public Path outputPath() {
        return Paths.get(outputDir);
    }
}
