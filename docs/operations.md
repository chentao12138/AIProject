# Operations

This document describes the operational surface added in BUSINESS-025.

The project intentionally stays infrastructure-light: no external monitoring
server, collector, or broker is required.

## Health probes

| Endpoint | Purpose |
| --- | --- |
| `/actuator/health` | Combined liveness + readiness summary |
| `/actuator/health/liveness` | Process alive; must NOT depend on DB/storage |
| `/actuator/health/readiness` | Can this instance serve traffic? |

Readiness depends on:

- database connectivity
- local storage root availability

Liveness answers only whether the JVM process is alive.

The legacy SPIKE `/health` endpoint is preserved unchanged.

## Storage health indicator

`/actuator/health/storage-health` reports local backend health.

The response contains only bounded metadata:

- `backend = local`
- `up = true/false`
- optional non-sensitive reason

No physical path, no absolute root, and no secret is exposed.

## Actuator security

| Endpoint group | Anonymous access |
| --- | --- |
| `/actuator/health` | yes |
| `/actuator/health/liveness` | yes |
| `/actuator/health/readiness` | yes |
| `/actuator/health/storage-health` | yes |
| `/actuator/info` | yes |
| `/actuator/prometheus` | no (ADMIN only) |

Spring Security orders the actuator chain first (`@Order(0)`) using
`EndpointRequest.toAnyEndpoint()`. The production `/api/v1/**` chain
remains at `@Order(2)` and continues to use `authJwtDecoder`.

No session, basic-auth, or SPIKE trust-domain access is granted to
actuator endpoints.

## Exposed actuator endpoints

Default exposure:

- `health`
- `info`
- `prometheus`

These endpoints are explicitly disabled:

- `env`
- `configprops`
- `beans`
- `heapdump`
- `threaddump`
- `mappings`
- `loggers`
- `shutdown`

The shutdown endpoint remains disabled.

## Request correlation id

Every HTTP request receives a bounded `X-Request-Id` header.

Behavior:

- Valid incoming `X-Request-Id` (`[A-Za-z0-9._-]{1,64}`) is preserved.
- Invalid or absent ids are replaced with a server-generated UUID-based id.
- The effective id is returned in the response `X-Request-Id` header.
- The id is placed in MDC (`requestId`) for the request lifetime only.
- MDC is cleared in `finally` to avoid thread-local leakage.

The console logging pattern includes `requestId=%X{requestId:-}`.

## Metrics

Standard Spring Boot / Micrometer metrics are available automatically:

- JVM
- process
- HTTP server requests
- datasource pool
- system

Custom product metrics use bounded tags only.

Ingestion metrics:

- `aistudy.ingestion.jobs` with tags `type`, `outcome`
- `aistudy.ingestion.failures` with tags `type`, `error_code`

Auth, search, and storage metrics are intentionally lightweight or omitted
in this phase to avoid invasive auth/search rewrites.

Forbidden metric tag values:

- user subject
- username/email
- requestId
- assetId/sourceId/questionId
- search query text
- storageKey
- filename
- exception message

## Storage reconciliation

### Endpoint

`POST /api/v1/admin/operations/storage/reconcile`

Requires ADMIN.

Optional JSON body:

```
{ "mode": "CLEANUP" }
```

When the body is absent or `mode` is `DRY_RUN`, the endpoint runs in
read-only dry-run mode and returns candidate orphans without deleting them.
Only explicit `mode: "CLEANUP"` deletes files.

### Default mode

DRY RUN.

The endpoint returns counts only:

- `scanned`
- `referenced`
- `candidateOrphans`
- `deleted`
- `skippedRecent`
- `skippedUnsafe`
- `truncated`
- `mode` (`DRY_RUN` or `CLEANUP`)

### Bounds

Configuration:

- `aistudy.operations.storage-reconciliation.max-scan-entries` (default `10000`)
- `aistudy.operations.storage-reconciliation.min-age` (default `PT1H` / 1 hour)

The filesystem scan is bounded by `max-scan-entries`. After scanning, the
service queries `source_asset.storage_key` only for the bounded set of
observed keys instead of selecting distinct keys from the entire table.

### Safety rules

- never auto-deletes files on startup
- never follows symlinks
- never scans outside the configured storage root
- never accepts caller-supplied paths or storage key lists
- never returns absolute filesystem paths
- skips files too recent to be outside the transaction grace window
- skips non-regular files, directories, and symlinks

## Stale ingestion jobs

A job whose worker dies is reclaimed by a lease, not by a restart:

- the worker atomically claims `QUEUED → IMPORTING` and renews `claimed_at`
  between assets (`heartbeat`);
- `requeueStaleProcessing` requeues any non-terminal job whose claim is older
  than `stale-lease` (default `PT30M`);
- recovery runs at startup **and** every `recovery-interval-ms` (default 60s),
  so a crash no longer strands a job until the next boot.

Re-running a job is expected to be safe: completed stages are skipped via
`last_stage_status`, and re-extraction deletes the asset's un-published
pages/blocks before inserting. Residual: a ZIP re-extract inserts fresh
`source_asset` rows per entry, so repeated retries of a ZIP source multiply
those rows (tracked, not yet fixed).

Knobs: `runtime-configuration.md` §Ingestion worker. Setting
`AISTUDY_INGESTION_WORKER_RECOVERY_ENABLED=false` stops the periodic tick; the
`test` and `flyway-it` profiles do that so tests drive job state by hand.

## Residual risks

- No distributed tracing backend.
- No external logging stack.
- Prometheus endpoint is ADMIN-protected; deployment must still use
  network policy to restrict scrape access.
- Storage cleanup is manual and bounded; operators must not rely on
  automatic orphan removal.

## Graceful shutdown

The production profile enables `server.shutdown=graceful` with
`spring.lifecycle.timeout-per-shutdown-phase=30s`. Active requests are
allowed to complete before the context closes. Operators should pair this
with reverse-proxy timeout / retry settings so draining connections do
not surface as client-facing 502s.

## Reconciliation deployment policy

`POST /api/v1/admin/operations/storage/reconcile` is the only supported
storage-reconciliation surface.

Default mode is `DRY_RUN`. `CLEANUP` is an explicit, authenticated,
audited admin action. There is no scheduled destructive cleanup and no
automatic startup cleanup. Cleanup respects `aistudy.operations.storage-reconciliation.min-age`
(default `PT1H`) so recently uploaded assets are never orphaned by a
race between upload and reconcile.

## First-admin bootstrap

When `AISTUDY_BOOTSTRAP_ADMIN_ENABLED=true`, the `BootstrapAdminRunner`
creates the first ADMIN from environment-provided credentials.

Rules:

- enabled=true + valid fields + no ADMIN -> create account with USER+ADMIN.
- enabled=true + ADMIN already exists -> no-op; do not touch existing ADMIN.
- enabled=true + missing fields -> fail startup with `IllegalStateException`.
- enabled=false -> no-op.

Concurrency is handled by counting active ADMIN accounts before and after
creation. If a concurrent bootstrap wins the race, the runner no-ops.
Plaintext passwords are never logged, and bootstrap credentials are not
persisted after the account is created.
