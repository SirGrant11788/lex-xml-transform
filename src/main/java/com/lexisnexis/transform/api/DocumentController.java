package com.lexisnexis.transform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexisnexis.transform.ingest.BatchOutcome;
import com.lexisnexis.transform.ingest.IngestOutcome;
import com.lexisnexis.transform.ingest.IngestService;
import com.lexisnexis.transform.model.NormalizedJudgment;
import com.lexisnexis.transform.publish.FileSystemPublisher;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * REST surface for the transformation service.
 *
 * <p>Endpoints are mounted under {@code /api/v1} and follow pragmatic
 * conventions:
 * <ul>
 *   <li>Single ingest returns 202 (Accepted) for any well-formed payload —
 *       the outcome (published vs invalid) is in the body, not the status.</li>
 *   <li>Batch ingest returns 200 with an aggregate summary.</li>
 *   <li>Retrieval returns 200 with the JSON artifact, or 404 when absent.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final IngestService ingest;
    private final FileSystemPublisher publisher;
    private final ObjectMapper mapper;

    public DocumentController(IngestService ingest, FileSystemPublisher publisher, ObjectMapper mapper) {
        this.ingest = ingest;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    /** Submit a single XML document. Body is the raw XML. */
    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<Dtos.IngestResponse> ingestSingle(@RequestBody byte[] body) {
        if (body == null || body.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty body");
        }
        IngestOutcome outcome = ingest.process(body);
        var response = new Dtos.IngestResponse(
                outcome.status(), outcome.contentId(), outcome.message(),
                outcome.artifactPath(), outcome.sha256(), outcome.diagnostics());
        return ResponseEntity.accepted().body(response);
    }

    /** Submit a batch by referencing a server-side folder of XML files. */
    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Dtos.BatchResponse> ingestBatchJson(@Valid @RequestBody Dtos.BatchFolderRequest req) throws IOException {
        Path folder = Path.of(req.folderPath());
        BatchOutcome outcome = ingest.processFolder(folder);
        return ResponseEntity.ok(toResponse(outcome));
    }

    /** Submit a batch by uploading a zip of XML files. */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Dtos.BatchResponse> ingestBatchZip(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty upload");
        }
        Path tmp = Files.createTempDirectory("lex-batch-");
        try (var zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (!entry.getName().toLowerCase().endsWith(".xml")) continue;
                Path out = tmp.resolve(Path.of(entry.getName()).getFileName());
                Files.write(out, zis.readAllBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
        BatchOutcome outcome = ingest.processFolder(tmp);
        return ResponseEntity.ok(toResponse(outcome));
    }

    /** Retrieve the status + published artifact for a given content_id. */
    @GetMapping("/{contentId}")
    public ResponseEntity<Dtos.StatusResponse> status(@PathVariable String contentId) throws IOException {
        Path json = publisher.validDir().resolve(contentId + ".json");
        if (!Files.exists(json)) {
            // Surface invalid records if present
            Path invalid = publisher.invalidDir().resolve(contentId + ".errors.json");
            if (Files.exists(invalid)) {
                return ResponseEntity.ok(new Dtos.StatusResponse(contentId,
                        com.lexisnexis.transform.model.DocumentStatus.INVALID, null, null));
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown content_id: " + contentId);
        }
        NormalizedJudgment judgment = mapper.readValue(json.toFile(), NormalizedJudgment.class);
        return ResponseEntity.ok(new Dtos.StatusResponse(contentId,
                com.lexisnexis.transform.model.DocumentStatus.PUBLISHED,
                json.toString(), computeSha(json)));
    }

    private String computeSha(Path json) throws IOException {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(json));
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Dtos.BatchResponse toResponse(BatchOutcome o) {
        return new Dtos.BatchResponse(o.total(), o.published(), o.invalid(), o.duplicate(), o.failed(), o.files());
    }
}
