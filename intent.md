# LexisNexis XML-to-JSON Transformation Service

## Source Assignment
Senior Java Engineer Technical Vetting — Callie Kunst / OfferZen / LexisNexis
Reference PDF: `Senior_Java_Engineer_Assignment+(1).pdf`

## Objective
Build a Spring Boot 3.x service that ingests legal XML documents, validates them
against the provided XSD, transforms them with Saxon-HE XSLT 3.0 into a normalized
JSON record, and publishes the artifact locally. Batch processing must be supported
with configurable concurrency. The service must be containerized and the design
documented for one cloud (AWS chosen).

## Tech Stack
- Java 21 (assignment says 17+; 21 is the current LTS on Debian)
- Spring Boot 3.3.x (web, actuator, validation)
- Saxon-HE 12.x (XSLT 3.0 processor)
- Jackson (JSON serialization)
- Micrometer + Spring Actuator (metrics)
- JUnit 5 + Spring Boot Test + Mockito (testing)
- Maven 3.9.x build system
- Docker (container)
- AWS design notes (S3 + SQS + ECS Fargate pattern in SOLUTION.md)

## Functional Requirements
1. **Single-document ingest** — POST endpoint accepts XML body, content_id is
   read from inside the document (after validation); idempotent on content_id.
2. **Batch ingest** — POST endpoint accepts a folder path or multipart zip of
   XML files; processes them concurrently.
3. **Validation** — Each XML validated against the provided XSD
   (`schemas/judgment.xsd`). Failures recorded with diagnostics, never published.
4. **Transformation** — Valid documents transformed via Saxon-HE XSLT 3.0
   (`xslt/judgment-to-json.xsl`) producing normalized JSON plus a `full_text`
   plain-text variant suitable for RAG.
5. **Publishing** — Output written to `lex.ingest.output-dir` (default
   `./out/`) keyed by `content_id`. Idempotency: same `content_id` re-submitted
   produces the same artifact, never a duplicate.
6. **Status / retrieval** — GET endpoint returns processing status and the
   published JSON artifact (or 404 with diagnostic).

## Non-Functional Requirements
- Configurable concurrency: `lex.ingest.batch-concurrency` (default 4).
- Streaming XML parsing + streaming XSLT to keep memory flat on large docs.
- Health (`/actuator/health`), readiness (`/actuator/health/readiness`), info.
- Counters: documents received, valid, invalid, published, deduplicated.
- Timers: validation duration, transform duration, end-to-end.
- Structured logs (logback JSON in container; pretty in dev).

## Module Boundaries
```
com.lexisnexis.transform
├── api               // REST controllers + DTOs
├── ingest            // IngestService (single + batch, idempotency keys)
├── validate          // XsdValidator wrapper around javax.xml
├── transform         // SaxonTransformer + XSLT loading
├── publish           // FileSystemPublisher (keyed by content_id)
├── model             // Domain records (Judgment, NormalizedJudgment)
├── config            // @ConfigurationProperties (LexIngestProperties)
└── metrics           // Micrometer counters/timers
```

## API Surface
- `POST /api/v1/documents` — body: raw XML → 202 + status URL
- `POST /api/v1/documents/batch` — multipart file (zip) or JSON `{folderPath}`
- `GET /api/v1/documents/{contentId}` — status + JSON artifact (if published)
- `GET /actuator/health` — liveness
- `GET /actuator/health/readiness` — readiness
- `GET /actuator/metrics` — Prometheus-compatible

## Error Model
- `4xx` for client errors (malformed XML, missing content_id, batch folder empty)
- `422` for valid XML that fails XSD validation (with diagnostic list)
- `5xx` only for unexpected server faults
- `Problem` JSON shape (RFC 7807 style): `type`, `title`, `status`, `detail`,
  `instance`, optional `errors[]` with `field` and `message`.

## Data Storage Approach
Local filesystem for demo (per assignment). Layout:
```
out/
├── valid/<content_id>.json
├── valid/<content_id>.txt
└── invalid/<content_id>.errors.json
```
Idempotency key: SHA-256 of the canonicalized XML, with `content_id` as the
primary filename. A sidecar manifest (`out/manifest.json`) records
`{content_id, sha256, publishedAt, sourceFile}` to detect duplicates without
re-parsing.

## Cloud Plan (AWS — outlined in SOLUTION.md)
- **Inputs**: S3 bucket `lex-ingest-raw/` triggered by S3 Event Notifications
- **Trigger**: SQS queue `lex-ingest-jobs` between S3 and the service
- **Compute**: ECS Fargate behind ALB, 1-4 tasks auto-scaled on SQS depth
- **Outputs**: S3 bucket `lex-ingest-normalized/` partitioned by
  `s3://lex-ingest-normalized/{jurisdiction}/{year}/{content_id}.json`
- **RAG evolution**: emit `{content_id, s3_uri, sha256, full_text, embedding_ref}`
  records into OpenSearch; later add chunked embeddings via SageMaker/bedrock
- **Dedup**: S3 conditional writes (PUT-if-none-match) + DynamoDB lock table
- **Observability**: CloudWatch metrics from ECS task definition, structured
  JSON logs to CloudWatch Logs, alarms on invalid-rate and queue age

## Code Style
- 4-space indent, no tabs
- Java records for immutable DTOs; classes only when behavior matters
- SLF4J via Lombok-free `@Slf4j` annotation
- Constructor injection, no `@Autowired` field injection
- One public class per file, package-private helpers

## Testing Strategy
- **Unit** (`src/test/java`): validator, transformer, publisher with
  in-memory filesystem + sample XML fixtures
- **Slice** (`@WebMvcTest` for controllers, `@DataJpaTest` if any DB)
- **Integration** (`@SpringBootTest` with random port): full happy path
  + invalid XML + duplicate idempotency + batch of 10
- Coverage target: ≥ 80% on `validate`, `transform`, `publish`, `ingest`

## Boundaries
- Always: run `mvn verify` before commit; key public types with Javadoc
- Ask first: adding non-listed dependencies; changing JSON shape
- Never: commit `target/`, `.env`, secrets, or generated artifacts

## Success Criteria
1. `mvn clean verify` exits 0 with tests green
2. `mvn spring-boot:run` starts on :8080; `/actuator/health` returns UP
3. `curl -X POST --data-binary @sample.xml :8080/api/v1/documents` → 202
4. `GET /api/v1/documents/FR-2024-CA-000123` returns the published JSON
5. Invalid XML → 422 with diagnostics, no file written under `valid/`
6. Duplicate submission → same artifact, no duplicate, manifest unchanged
7. Batch endpoint processes N files concurrently with N = configured value
8. `docker build` produces a runnable image; `docker run` starts the service
9. README.md + SOLUTION.md present in repo root

## Deliverables
- `pom.xml`, `src/main/java/**`, `src/main/resources/**`, `src/test/java/**`
- `Dockerfile`, `.dockerignore`, optional `docker-compose.yml`
- `README.md` (run locally, run in docker, API examples)
- `SOLUTION.md` (design, trade-offs, AWS plan, RAG evolution)
- `examples/sample-judgment.xml` (matches assignment example)
- `examples/sample-batch/` (3-5 small XMLs for batch demo)
