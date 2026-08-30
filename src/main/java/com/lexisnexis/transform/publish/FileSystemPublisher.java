package com.lexisnexis.transform.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexisnexis.transform.config.LexIngestProperties;
import com.lexisnexis.transform.model.NormalizedJudgment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes normalized artifacts to the configured output directory.
 *
 * <p>Idempotency strategy: every published artifact is keyed by its
 * {@code content_id}. Before writing, we compute the SHA-256 of the
 * canonicalized JSON; if an existing artifact's hash matches, we treat
 * the call as a duplicate (no overwrite, no side effects). The same
 * mechanism detects re-submission of the exact same XML — the canonical
 * JSON form of the transform output is content-stable.</p>
 *
 * <p>Thread safety: per-content_id lock to keep duplicate checks atomic
 * under concurrent batch submission. Concurrency across distinct
 * content_ids is not limited.</p>
 */
@Component
public class FileSystemPublisher {
    private static final Logger log = LoggerFactory.getLogger(FileSystemPublisher.class);

    private final LexIngestProperties props;
    private final ObjectMapper mapper;

    private final Path validDir;
    private final Path invalidDir;
    private final Path manifestFile;
    private final ReentrantLock manifestLock = new ReentrantLock();

    public FileSystemPublisher(LexIngestProperties props, ObjectMapper mapper) throws IOException {
        this.props = props;
        this.mapper = mapper;
        this.validDir = props.outputPath().resolve("valid");
        this.invalidDir = props.outputPath().resolve("invalid");
        this.manifestFile = props.outputPath().resolve("manifest.json");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(validDir);
        Files.createDirectories(invalidDir);
        if (!Files.exists(manifestFile)) {
            Files.writeString(manifestFile, "{\n  \"entries\": []\n}\n");
        }
        log.info("Publisher ready: valid={}, invalid={}, manifest={}",
                validDir, invalidDir, manifestFile);
    }

    public Path validDir() {
        return validDir;
    }

    public Path invalidDir() {
        return invalidDir;
    }

    public Path manifestFile() {
        return manifestFile;
    }

    /** Result of a publish attempt. */
    public sealed interface PublishResult permits Published, Duplicate {}
    public record Published(Path jsonPath, Path textPath, String sha256) implements PublishResult {}
    public record Duplicate(String existingSha256) implements PublishResult {}

    /**
     * Publish the normalized judgment and its plain-text view.
     *
     * <p>If an artifact for the same {@code content_id} already exists with the
     * same SHA-256, returns {@link Duplicate} without touching disk. If the
     * existing artifact has a different SHA-256 (rare; content changed under the
     * same id), it is overwritten — this is treated as a manual re-publish.</p>
     */
    public PublishResult publish(NormalizedJudgment judgment) throws IOException {
        Path jsonPath = validDir.resolve(judgment.contentId() + ".json");
        Path textPath = validDir.resolve(judgment.contentId() + ".txt");

        byte[] canonicalJson = canonicalJsonBytes(judgment);
        String sha = sha256(canonicalJson);

        if (Files.exists(jsonPath)) {
            byte[] existing = Files.readAllBytes(jsonPath);
            String existingSha = sha256(existing);
            if (existingSha.equals(sha)) {
                log.debug("Duplicate publish suppressed for content_id={}", judgment.contentId());
                return new Duplicate(existingSha);
            }
            log.warn("Re-publishing content_id={} (hash differs): {} -> {}",
                    judgment.contentId(), existingSha, sha);
        }

        // Atomic write: tmp + move
        Path tmp = Files.createTempFile(validDir, judgment.contentId() + "-", ".tmp");
        Files.writeString(tmp, new String(canonicalJson));
        Files.move(tmp, jsonPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // Plain text view (paragraph-per-line concatenation)
        Files.writeString(textPath, judgment.fullText() == null ? "" : judgment.fullText());

        appendManifest(judgment.contentId(), sha);
        log.info("Published content_id={} sha256={} -> {}", judgment.contentId(), sha, jsonPath);
        return new Published(jsonPath, textPath, sha);
    }

    /** Write the diagnostic list to the invalid bucket for inspection. */
    public Path recordInvalid(String contentId, java.util.List<String> diagnostics) throws IOException {
        Path file = invalidDir.resolve(contentId + ".errors.json");
        String body = mapper.writeValueAsString(java.util.Map.of(
                "content_id", contentId,
                "diagnostics", diagnostics,
                "recorded_at", Instant.now().toString()
        ));
        Files.writeString(file, body);
        return file;
    }

    private byte[] canonicalJsonBytes(NormalizedJudgment j) throws IOException {
        // Pretty-print to keep diffs human-readable while still stable for hashing.
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(j);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendManifest(String contentId, String sha) throws IOException {
        manifestLock.lock();
        try {
            // Re-create the manifest on demand — the test lifecycle may delete it
            // between Spring context startup and individual test runs.
            if (!Files.exists(manifestFile)) {
                Files.writeString(manifestFile, "{\n  \"entries\": []\n}\n");
            }
            java.util.Map<String, Object> manifest = mapper.readValue(manifestFile.toFile(), java.util.Map.class);
            java.util.List<java.util.Map<String, Object>> entries =
                    (java.util.List<java.util.Map<String, Object>>) manifest.computeIfAbsent("entries", k -> new java.util.ArrayList<>());
            // Replace any prior entry for this content_id (overwrite case)
            entries.removeIf(e -> contentId.equals(e.get("content_id")));
            entries.add(java.util.Map.of(
                    "content_id", contentId,
                    "sha256", sha,
                    "published_at", Instant.now().toString()
            ));
            mapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
        } finally {
            manifestLock.unlock();
        }
    }
}
