# Loom Demo Script — lex-xml-transform

**Target length:** 8 minutes. **Tone:** senior peer-to-peer, conversational,
South African English is fine. **Don't read code line-by-line — talk about
*why*.**

---

## Cold open (~30s)

**On screen:** terminal with a fresh `curl` against the running service.

> Hi — I'm Grant, and over the last few hours I built a small Spring Boot
> service that takes legal XML documents, validates them against the
> LexisNexis judgment XSD, transforms them with Saxon-HE XSLT 3.0, and
> publishes a normalized JSON record ready for search and RAG. It batches,
> it dedups, it containerises, and it ships with a concrete AWS plan. Let
> me walk you through how it's put together and why I made the choices I
> did.

---

## 0:00 – 1:00 — The shape of the service

**On screen:** `tree -L 3 src/main/java/com/lexisnexis/transform`

> The first thing to know is the module boundaries. I split the pipeline
> into four single-responsibility beans — `XsdValidator`,
> `SaxonTransformer`, `FileSystemPublisher` — and an orchestrator
> `IngestService` that wires them together. That means I can unit-test each
> stage in isolation, and swapping the filesystem publisher for an S3 one
> later is a focused refactor, not a rewrite.

> Two things drive the public contract: `LexIngestProperties` — every
> config value is env-overridable via Spring relaxed binding, so the same
> Docker image runs in dev, staging, and prod — and `IngestMetrics`,
> which gives me a small set of Micrometer counters and timers that
> surface everything I'd want to alert on in production.

**Key takeaway:** single-responsibility beans + env-overridable config +
real metrics = a service that's actually operable.

---

## 1:00 – 2:00 — XSD validation that doesn't lie

**On screen:** `src/main/java/.../validate/XsdValidator.java`, then `examples/sample-batch/04-invalid.xml`.

> Validation uses the JDK's built-in `javax.xml.validation.Validator`.
> That's a deliberate choice — no extra dependency, fully streaming, and
> it captures every SAX diagnostic so the client gets a complete list of
> failures, not just the first one. Watch what happens with an invalid
> document.

**Action:** run the live curl from earlier — the `INVALID` response shows
the four diagnostic lines, and there's a corresponding record under
`out/invalid/<content_id>.errors.json` for the data team to inspect
later. We don't publish garbage downstream.

**Key takeaway:** collect every diagnostic, never silently drop on first
error, and keep the bad input around so it's auditable.

---

## 2:00 – 3:30 — The XSLT: why it doesn't emit JSON directly

**On screen:** `xslt/judgment-to-json.xsl`, then `SaxonTransformer.java`.

> Here's the most interesting trade-off in the project. The assignment
> says "transform via XSLT executed by Saxon-HE" — but Saxon-HE doesn't
> ship the JSON-output emitter that Saxon-PE and EE do. If I declared
> `xsl:output method="json"` and built the canonical `<map>` / `<array>`
> tree, Saxon-HE would just stringify the elements inside a JSON string
> literal — useless.

> So I split the work: the XSLT emits an **intermediate XML record** with
> a predictable shape — `<contentId>`, `<citations>`, etc. — and the
> Java side parses that with the JDK DOM and hands the domain model to
> Jackson. XSLT still does the structured extraction, Jackson handles
> date formatting and the snake-case JSON keys we want on the wire. It's
> a clean split and it's portable across Saxon editions.

> The second thing the XSLT does is fire a secondary `xsl:result-document`
> to `href="text:full"`. That's where the plain-text RAG view comes
> from — paragraph concatenation, ready for chunking and embedding
> later. I route that URI through a `Function<URI, Destination>` on the
> transformer so it lands in an in-memory serializer without a second
> XML parse on the Java side.

**Action:** open `SaxonTransformer.java` lines around the
`setResultDocumentHandler` call.

**Key takeaway:** know your tools' limitations and design around them —
don't fight the library.

---

## 3:30 – 4:30 — Idempotency you can actually trust

**On screen:** `publish/FileSystemPublisher.java`, then `manifest.json`
from `out/`, then the live curl showing `PUBLISHED` → `DUPLICATE`.

> Idempotency is the second thing I'd build into any ingest pipeline. I
> use two layers. First, the filename is the `content_id` — same id
> always overwrites the same file, no proliferation. Second, before any
> write I SHA-256 the canonicalised JSON. If the existing artifact's hash
> matches, the publish is suppressed entirely — no filesystem touch, no
> manifest churn, no metric drift. If the hash differs, we overwrite and
> log a WARN.

> The manifest itself is updated under a single lock so concurrent
> publishes for different ids don't race on the JSON write. And if
> somebody deletes the manifest by accident, we recreate it on demand —
> that's not just test-friendliness, it's operator-friendly.

> Here's the live demo — submit the same XML twice. First call:
> `PUBLISHED`. Second call: `DUPLICATE`, same SHA, no second file.

**Key takeaway:** dedup at the content layer, not the request layer —
that's what survives retries, restarts, and concurrent submitters.

---

## 4:30 – 5:30 — Batch, concurrency, metrics

**On screen:** `IngestService.processFolder`, then a live batch call.

> The batch endpoint fans out across a fixed thread pool — default four,
> overridable via `LEX_INGEST_BATCH_CONCURRENCY`. Each file is
> independent, so a poison message doesn't fail the whole batch. The
> aggregate response gives you counts: total, published, invalid,
> duplicate, failed — exactly what you want to chart in a dashboard.

> Every stage of the pipeline exposes Micrometer instruments. Watch the
> counters tick when I run the batch.

**Action:** `curl /actuator/metrics/lex.ingest.documents.published` etc.

> And the timers — `lex.ingest.transform.duration` — are how I'd build a
> tail-latency SLO on the hot path. No black box.

**Key takeaway:** make the boring operational things boring.

---

## 5:30 – 6:30 — Containerization, config, and the small surprises

**On screen:** `Dockerfile`, then `application.yml`, then the env-var
override pattern.

> The Dockerfile is a standard two-stage build — Maven build with a
> cache mount, then a JRE-only runtime as non-root `lex` with a
> HEALTHCHECK against the actuator. No magic.

> All paths and the concurrency knob are env-overridable, so the image
> runs anywhere. Watch — `SERVER_PORT=8090 LEX_INGEST_BATCH_CONCURRENCY=8`
> and the same jar runs on a different port with a bigger pool.

> The one thing that bit me on the way: Spring Boot's relaxed binding on
> `spring.jackson.serialization` rejects `write-dates-as-strings` as an
> unknown enum value — it expects the `WRITE_DATES_AS_TIMESTAMPS` enum
> name, not a YAML hyphen-case alias. That's in `SOLUTION.md` so the
> next person doesn't lose twenty minutes to it.

**Key takeaway:** externalised config + non-root container +
healthcheck = production-ready, not just "works on my laptop."

---

## 6:30 – 7:30 — The AWS plan

**On screen:** `SOLUTION.md`, scrolling through section 4.

> The cloud plan in `SOLUTION.md` is the bit I'd want a senior IC to
> think through on day one. It's AWS because that's where the LexisNexis
> estate lives. Inputs land in an S3 bucket, S3 events notify an SQS
> queue, ECS Fargate tasks poll the queue and run the same
> `IngestService.process(bytes)` — no rewrite needed. Output goes to a
> separate normalized bucket partitioned by jurisdiction and year.

> Duplicate prevention stacks: SHA-256 inside the service, S3
> conditional writes (`If-None-Match: *`) at the storage layer, and an
> optional DynamoDB lock table when traffic warrants it. Each layer is
> independent; you can drop any of them without losing the others.

> For the RAG evolution, the `full_text` field we already emit is the
> starting point. The plan adds chunked artifacts, a Bedrock embedding
> step, and an OpenSearch indexer — all provenance-tagged back to the
> canonical SHA so a RAG answer can always cite the source.

**Key takeaway:** design for the next two orders of magnitude of
traffic now, while the codebase is small enough to change cheaply.

---

## 7:30 – 8:00 — Closing

**On screen:** GitHub repo placeholder.

> The whole thing — source, tests, Dockerfile, design notes — is in
> the repo linked in the description. `mvn verify` builds and tests
> green; the README walks through every endpoint; `SOLUTION.md` covers
> the trade-offs I made and why.

> Two things I'd love feedback on: first, the Saxon-HE → Jackson split
> for the JSON shape — is there a cleaner way I'm missing? Second,
> whether the SHA-256 dedup belongs in the service or further out at
> the queue layer. Both are in `SOLUTION.md` as open questions.

> Thanks for watching.

**Key takeaway:** solid, observable, idempotent, and documented. That's
what "production-style" means to me.

---

## If you only have 90 seconds (social cut)

1. **Why Saxon-HE + Jackson for JSON?** Saxon-HE's open-source edition
   doesn't ship the JSON emitter — the XSLT extracts structure, Jackson
   serialises. One decision, fully portable.
2. **Two-layer dedup.** SHA-256 over canonicalised JSON, plus S3
   `If-None-Match: *` in cloud. Survives retries, restarts, concurrent
   submitters.
3. **Operability is a feature.** `/actuator/health`,
   `lex.ingest.transform.duration`, env-override everything. Boring on
   purpose.
