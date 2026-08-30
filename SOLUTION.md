# SOLUTION.md — LexisNexis XML-to-JSON Transformation Service

## 1. Context & goals

The French Content Systems team needs to ingest high volumes of legal
XML, validate against the provided judgment XSD, normalize into a
search- and AI-friendly JSON shape, and publish for downstream
search / RAG pipelines. This service implements that pipeline as a
production-style Spring Boot application with:

- **Correctness** — XSD validation is strict; invalid documents do not
  reach downstream consumers.
- **Idempotency** — repeated submission of the same `content_id`
  never produces a duplicate artifact.
- **Modularity** — every stage is a single-responsibility Spring bean
  (`XsdValidator`, `SaxonTransformer`, `FileSystemPublisher`,
  `IngestService`).
- **Operability** — health/readiness probes, custom Micrometer
  counters and timers, structured logging, externalized config, container
  image.
- **Throughput** — batch endpoint fans out across a configurable thread
  pool; XSLT compilation is amortized across requests.

---

## 2. Architecture

```
       ┌──────────────┐
HTTP → │ DocumentCtrl │ ──┐
HTTP → │  (REST API)  │   │
       └──────┬───────┘   │
              │           │ validate
              │           ▼
              │     ┌──────────────┐     transform      ┌────────────────┐
              │     │  XsdValidator│  ──────────────►   │ SaxonTransformer│ ─┐
              │     └──────────────┘                    └────────────────┘  │
              │                                                        publish │
              ▼                                                                ▼
       ┌──────────────┐                                            ┌──────────────────┐
       │ IngestService│ ───────── batch fan-out (ExecutorService) ──│ FileSystemPublisher│
       └──────────────┘                                            └──────────────────┘
              │                                                                │
              ▼                                                                ▼
       ┌──────────────┐                                            out/valid/<content_id>.json
       │IngestMetrics │                                            out/valid/<content_id>.txt
       └──────────────┘                                            out/manifest.json
                                                                    out/invalid/<...>.errors.json
```

### Module boundaries

| Package              | Responsibility                                                 |
|----------------------|-----------------------------------------------------------------|
| `api`                | REST controllers, DTOs, exception → HTTP mapping                |
| `ingest`             | Orchestration; peek content_id, validate, transform, publish    |
| `validate`           | XSD loading and validation, structured diagnostics               |
| `transform`          | Saxon compilation + execution, intermediate XML → JSON domain    |
| `publish`            | Atomic write of artifact + text; manifest + idempotency        |
| `metrics`            | Centralised Micrometer instruments                              |
| `config`             | `@ConfigurationProperties` + Jackson customisation              |
| `model`              | Pure records (`NormalizedJudgment`, `DocumentStatus`)           |

### Why these boundaries?

- **Validate / transform / publish are pure functions** of `(bytes) ->
  result`, no Spring coupling beyond constructor injection. Easy to unit
  test in isolation.
- **IngestService is the only orchestration point.** Adding a new
  transport (e.g. SQS-triggered Lambda) means writing a new entry point
  that calls `IngestService.process` — no duplication of pipeline logic.
- **FileSystemPublisher is the only component that touches the
  filesystem**, which makes a future swap to S3 (or any other object
  store) a focused refactor.

---

## 3. Key design decisions

### 3.1 XSLT emits intermediate XML; Jackson produces the final JSON

**Decision.** The XSLT stylesheet produces an intermediate XML record
(`<normalized>...</normalized>`). The Java side parses this with the JDK
DOM and serialises the domain model (`NormalizedJudgment`) to JSON via
Jackson.

**Why not `xsl:output method="json"`?** Saxon-HE (the open-source
edition) does **not** include the JSON-output emitter that ships with
Saxon-PE/EE. With Saxon-HE, a Serializer configured for method="json"
will serialise the canonical-JSON-tree elements (`<map>`,
`<array>`, `<string key="...">`) by simply stringifying them as XML
inside a JSON string — not what we want. Three options were
considered:

1. *Serialize XML, then transform with Jackson* (chosen).
   - Reliable across Saxon-HE / Saxon-PE / Saxon-EE.
   - Lets Jackson handle date/number formatting and key naming.
   - Tiny performance penalty (parse + serialise) is negligible vs. the
     XSLT compilation cost.
2. *Generate JSON literals directly in XSLT* (`<xsl:text>{"key":...}</xsl:text>`).
   - Brittle: JSON escaping, quoting, comma placement all become
     hand-managed.
3. *Hardcode `JsonBuilder` and Saxon-PE/EE*.
   - Incompatible with the assignment's Saxon-HE requirement.

### 3.2 RAG-friendly plain-text secondary output via `xsl:result-document`

The XSLT emits the plain-text RAG view to a secondary destination
(`href="text:full"`). A `Function<URI, Destination>` handler routes
that URI to an in-memory `Serializer` (method="text"). This avoids
extra XML parses in Java and keeps the XSLT as the single source of
truth for both outputs.

### 3.3 Idempotency by content_id + SHA-256

Two layers of dedup:

1. **`content_id` filenames.** Same id always overwrites the same file —
   no proliferation.
2. **SHA-256 over the canonicalized JSON.** If the existing artifact's
   hash matches the new one, the publish is **suppressed** (no
   filesystem write, no manifest entry). If the hash differs (someone
   modified the source XML under the same id), we overwrite and log a
   WARN. This makes the service safe under retry storms and concurrent
   submitters without a coordination service.

`manifest.json` is updated under a `ReentrantLock` so concurrent
publishes for different content_ids don't race on the file write. The
manifest is recreated on demand if missing (test-friendly + recovers
from operator error).

### 3.4 Streaming content_id peek

`IngestService.peekContentId` uses `javax.xml.stream` to read the first
`<content_id>` text node and stop. The full DOM is **not** built. This
keeps memory flat for large documents and surfaces the id before we
load the XSD validator on the bytes.

### 3.5 Concurrent batch processing

A fixed-size `ExecutorService` (`Executors.newFixedThreadPool(N)`) backs
batch ingestion. N is `LEX_INGEST_BATCH_CONCURRENCY` (default 4). Files
in a folder are sorted before fan-out to make output deterministic.
Each task is independent: a failure in one file does not affect the
others, and the aggregate result counts published / invalid / duplicate /
failed.

### 3.6 Structured metrics, not just logs

Every pipeline phase exposes Micrometer instruments:

| Metric                                | Type    | Tags                  |
|---------------------------------------|---------|-----------------------|
| `lex.ingest.documents.received`       | counter |                       |
| `lex.ingest.documents.valid`          | counter |                       |
| `lex.ingest.documents.invalid`        | counter |                       |
| `lex.ingest.documents.published`      | counter |                       |
| `lex.ingest.documents.duplicates`     | counter |                       |
| `lex.ingest.documents.by_status`      | counter | `status=PUBLISHED|...` |
| `lex.ingest.errors`                   | counter | `phase=transform|publish` |
| `lex.ingest.validation.duration`      | timer   |                       |
| `lex.ingest.transform.duration`       | timer   |                       |

These are exposed at `/actuator/metrics` and are scrape-ready for
Prometheus via a single dependency addition (`micrometer-registry-prometheus`).

### 3.7 Externalised configuration

Every operational knob is env-overridable via Spring's relaxed binding:

```
LEX_INGEST_XSD_PATH
LEX_INGEST_XSLT_PATH
LEX_INGEST_OUTPUT_DIR
LEX_INGEST_BATCH_CONCURRENCY
LEX_INGEST_MAX_XML_BYTES
SERVER_PORT
SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE
```

The same Docker image can be promoted across dev/stage/prod without a
rebuild.

---

## 4. AWS deployment plan

### 4.1 Topology

```
                       S3 Event Notifications
                              │
                              ▼
            ┌────────────────────────────────┐
            │  SQS queue: lex-ingest-jobs    │
            │  (visibility timeout 5 min)    │
            └────────────────┬───────────────┘
                             │
                             ▼
   ┌─────────────────────────────────────────────┐
   │  ECS Fargate service: lex-ingest            │
   │   • 1-4 tasks, CPU 1024 / Mem 2048          │
   │   • Auto-scale on SQS ApproximateNumberOfMessages
   │   • Task role → S3 read/write on lex-ingest-*│
   └────────────────┬────────────────────────────┘
                    │
            ┌───────┴────────┐
            ▼                ▼
   S3: lex-ingest-       S3: lex-ingest-
       raw/                   normalized/
   (incoming XML)            (published JSON)
                             partitioned by
                             {jurisdiction}/{year}/{content_id}.json
```

### 4.2 Inputs

`lex-ingest-raw/<source>/<yyyy>/<mm>/<dd>/<file>.xml` — files land via
direct upload, SFTP drop, or partner API gateway. S3 Event
Notifications publish a message to SQS per object (with `s3:ObjectCreated:*`
events).

### 4.3 Trigger

A single SQS queue (`lex-ingest-jobs`) between S3 and the service:

- Decouples bursty upload patterns from the service's processing rate.
- Lets the service scale on queue depth (CloudWatch alarm
  `AWS/SQS ApproximateNumberOfMessagesVisible > 1000` → scale out).
- Provides dead-letter queue for poison messages
  (`lex-ingest-jobs-dlq`) after `maxReceiveCount=5`.

Each Fargate task polls SQS with a long-poll (20s), downloads the
referenced S3 object, invokes the same `IngestService.process(bytes)`
pipeline, and deletes the message on success.

### 4.4 Outputs

`s3://lex-ingest-normalized/{jurisdiction}/{year}/{content_id}.json`
— partitioning by jurisdiction + year keeps individual prefix sizes
manageable for downstream consumers (Athena queries, OpenSearch
ingestion). The companion `lex-ingest-text/` prefix holds the
plain-text RAG views.

S3 conditional writes (`If-None-Match: *` + versioned bucket) provide
the cross-instance dedup guarantee at the storage layer:

```java
s3.putObject(req -> req
    .bucket("lex-ingest-normalized")
    .key(key)
    .ifNoneMatch("*")
    .contentType("application/json"),
    RequestBody.fromBytes(jsonBytes));
```

A 412 Precondition Failed signals "already published — skip."

### 4.5 Monitoring

- **CloudWatch metrics** — every container exposes Prometheus on
  `/actuator/prometheus` via the embedded server; a sidecar
  (CloudWatch Agent with Prometheus support) scrapes and forwards to
  CloudWatch as custom metrics.
- **Dashboards** — three panels: throughput (published/min), error
  rate (`invalid / received`), and tail latency (p95
  `lex.ingest.transform.duration`).
- **Alarms**
  - `InvalidRate > 0.10` for 10 min → partner-data-quality page.
  - `ApproximateAgeOfOldestMessage > 600s` → page on-call.
  - `5xx rate > 1%` over 5 min → page on-call.

### 4.6 Duplicate prevention across the fleet

| Layer                     | Mechanism                                              |
|---------------------------|--------------------------------------------------------|
| Application (this service)| SHA-256 of canonicalized JSON                          |
| Object storage (S3)       | `If-None-Match: *` on PutObject                        |
| Coordination (optional)   | DynamoDB conditional write on `{content_id}` partition |

The DynamoDB table is the strongest guarantee (single-writer per
content_id). Recommended for production: turn on DynamoDB idempotency
once the service is past initial traffic levels. Until then, S3
conditional writes are sufficient and avoid a new dependency.

### 4.7 RAG evolution

Today the published JSON includes `full_text` (concatenated
paragraphs). To feed a RAG pipeline:

1. **Chunking** — emit additional artifacts at
   `s3://lex-ingest-chunks/{jurisdiction}/{year}/{content_id}/{n}.json`
   with shape `{content_id, chunk_id, section, paragraph_ids, text}`.
   Done either by extending the XSLT or a post-processing Lambda.
2. **Embeddings** — a second Fargate task consumes `lex-ingest-chunks/`,
   calls Bedrock / SageMaker endpoint, writes
   `s3://lex-ingest-vectors/{...}.json` (`vector: number[], model, version`).
3. **Index** — an OpenSearch indexer Lambda tails the vector bucket and
   upserts into an OpenSearch index with the BM25 + kNN hybrid
   configuration.
4. **Provenance** — every chunk artifact includes `{source_sha256,
   xsd_version, xslt_version, transformed_at, model_id, embedding_dim}`
   so RAG answers can cite back to the canonical artifact.

### 4.8 Cost notes

- Saxon-HE compiled stylesheet amortised over many requests keeps
  per-document CPU low. Fargate Spot + SQS gives burst-friendly cost.
- S3 + SQS are pay-per-use; for a steady 100 docs/sec the monthly
  cost is dominated by Fargate compute, not storage or messaging.
- CloudWatch logs use a 30-day retention + a tighter (7-day) hot tier
  to control costs.

---

## 5. Trade-offs and known limitations

| Decision                                  | Trade-off                                              |
|-------------------------------------------|--------------------------------------------------------|
| Local filesystem for demo (`./out/`)      | Not horizontally scalable; S3 is the production swap   |
| Fixed-size thread pool (not virtual)      | Predictable, easy to reason about; bound by core count |
| DOM walk of intermediate XML              | Fine for one-doc-per-request; switch to StAX for huge  |
| Saxon-HE 12 (not Saxon-PE/EE)             | No JSON-output emitter; mitigated via Jackson          |
| No external dedup store (DynamoDB yet)    | S3 conditional writes carry the production load       |
| No auth / mTLS at the boundary           | Out of scope for the assignment; add at ALB / API GW   |
| Single-region deployment                  | Documented; cross-region fan-out would add an SQS      |
|                                           | region pair and per-region Fargate service             |

---

## 6. Verification

- `mvn verify` → BUILD SUCCESS, 9 tests pass (unit + integration + REST).
- Live curl smoke against a packaged JAR (port 8090): all six
  documented scenarios pass; metrics tick correctly
  (`received=7, valid=5, invalid=2, published=4, duplicates=1`).
- `docker build` produces a runnable image; `docker run` starts the
  service end-to-end.

---
