# Runtime Configuration

This document lists environment variables actually bound by the AIStudy server.
Do not document variables that are not implemented.

Unless noted, variables are consumed via `application.yml` / profile YAML
and override the Spring Boot defaults declared there.

## Profiles

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | all | no | (none) | profile name(s), comma-separated | no |

## Database

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `DB_URL` | local, prod, it | yes for local / prod / it | (none) | JDBC URL | no |
| `DB_USERNAME` | local, prod, it | yes for local / prod / it | (none) | string | no |
| `DB_PASSWORD` | local, prod, it | yes for prod / it | (none) | string | yes |

## Flyway integration tests

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `FLYWAY_DB_URL` | flyway-it | yes | (none) | JDBC URL | no |
| `FLYWAY_DB_USERNAME` | flyway-it | yes | (none) | string | no |
| `FLYWAY_DB_PASSWORD` | flyway-it | yes | (none) | string | yes |

`application-flyway-it.yml` intentionally disables Spring Boot auto-migration
(`spring.flyway.enabled=false`). Tests drive Flyway through the Java API.

## Server binding

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `SERVER_ADDRESS` | local, prod | no | `0.0.0.0` (prod), `127.0.0.1` (local) | IP address | no |
| `SERVER_PORT` | local, prod | no | `8080` | port | no |

## Auth / JWT

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AUTH_JWT_SECRET` | all | yes for local / prod | (none) | Base64-encoded HMAC secret (>= 32 bytes after decode for HS256) | yes |
| `AUTH_JWT_ACCESS_TOKEN_TTL_SECONDS` | all | no | `900` | seconds | no |
| `AUTH_REFRESH_TOKEN_TTL_SECONDS` | all | no | `2592000` | seconds | no |

Startup fails if `AUTH_JWT_SECRET` is absent or decodes to fewer than 256
bits — in **any** profile, not only production. `AuthJwtEncoder` is an
unconditional `@Component`, so a `local` boot with an empty secret dies with
`AUTH_JWT_SECRET is required for production JWT signing`; the message names
production but the check does not gate on the profile.

`application-test.yml` and `application-flyway-it.yml` supply deterministic
test-only defaults so the context can boot without secrets. `local` has no
default: export a throwaway Base64 value and never reuse a production secret.

## Storage

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AISTUDY_STORAGE_LOCAL_ROOT` | local, prod | yes for prod | `${user.home}/.aistudy/resources` | absolute directory path | no |

Blank or missing `AISTUDY_STORAGE_LOCAL_ROOT` fails startup with an
`IllegalStateException`. Production must supply a real writable directory.

## Upload / multipart

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AISTUDY_UPLOAD_MAX_FILE_SIZE` | all | no | `1024MB` | Spring `DataSize` | no |
| `AISTUDY_UPLOAD_MAX_REQUEST_SIZE` | all | no | `1040MB` | Spring `DataSize` | no |

`spring.servlet.multipart.max-file-size` and `spring.servlet.multipart.max-request-size`
follow the same values. The request size is intentionally larger than the file
size to absorb multipart protocol overhead.

## CORS

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AISTUDY_CORS_ALLOWED_ORIGINS` | local, prod | yes for prod | (empty) | comma-separated origins | no |

An empty list means no cross-origin requests are allowed. The local profile
explicitly allows development origins. Production must provide explicit origins.
`*` is never used.

## Storage reconciliation

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AISTUDY_OPS_RECONCILE_MAX_SCAN` | all | no | `10000` | count | no |
| `AISTUDY_OPS_RECONCILE_MIN_AGE` | all | no | `PT1H` | ISO-8601 duration | no |

Reconciliation bounds are enforced in `StorageReconciliationService` by
scanning at most `max-scan-entries` files and only considering files older
than `min-age` to avoid racing with in-flight uploads.

## Ingestion limits

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AISTUDY_INGESTION_ZIP_MAX_ENTRIES` | all | no | `10000` | count | no |
| `AISTUDY_INGESTION_ZIP_MAX_ENTRY_BYTES` | all | no | `4GB` | Spring `DataSize` | no |
| `AISTUDY_INGESTION_ZIP_MAX_TOTAL_BYTES` | all | no | `16GB` | Spring `DataSize` | no |
| `AISTUDY_INGESTION_ZIP_MAX_RATIO` | all | no | `200` | ratio (percentage / 100) | no |
| `AISTUDY_INGESTION_TEXT_MAX_DOCUMENT_BYTES` | all | no | `64MB` | Spring `DataSize` | no |
| `AISTUDY_INGESTION_PDF_MAX_BYTES` | all | no | `100MB` | Spring `DataSize` | no |
| `AISTUDY_INGESTION_PDF_MAX_PAGES` | all | no | `200` | pages | no |
| `AISTUDY_INGESTION_PDF_MAX_EXTRACTED_CHARS` | all | no | `2000000` | characters | no |
| `AISTUDY_INGESTION_IMAGE_MAX_BYTES` | all | no | `50MB` | Spring `DataSize` | no |
| `AISTUDY_INGESTION_IMAGE_MAX_WIDTH` | all | no | `10000` | pixels | no |
| `AISTUDY_INGESTION_IMAGE_MAX_HEIGHT` | all | no | `10000` | pixels | no |
| `AISTUDY_INGESTION_IMAGE_MAX_PIXELS` | all | no | `100000000` | pixels | no |

## Ingestion worker / job lease

The in-process ingestion pool and its recovery lease (architecture.md §6.2).

| Variable | Property | Profiles | Default | Meaning |
| --- | --- | --- | --- | --- |
| `AISTUDY_INGESTION_WORKER_CORE_SIZE` | `aistudy.ingestion.worker.core-size` | all | `2` | resident worker threads |
| `AISTUDY_INGESTION_WORKER_MAX_SIZE` | `aistudy.ingestion.worker.max-size` | all | `4` | max worker threads |
| `AISTUDY_INGESTION_WORKER_QUEUE_CAPACITY` | `aistudy.ingestion.worker.queue-capacity` | all | `64` | overflow beyond this stays QUEUED in the DB instead of piling up in heap |
| `AISTUDY_INGESTION_WORKER_SHUTDOWN_GRACE` | `aistudy.ingestion.worker.shutdown-grace` | all | `PT20S` | time in-flight jobs get during graceful shutdown (must stay below `spring.lifecycle.timeout-per-shutdown-phase`) |
| `AISTUDY_INGESTION_WORKER_STALE_LEASE` | `aistudy.ingestion.worker.stale-lease` | all | `PT30M` | claim age after which recovery may requeue the job |
| `AISTUDY_INGESTION_WORKER_HEARTBEAT` | `aistudy.ingestion.worker.heartbeat` | all | `PT5M` | how often a running worker renews its claim |
| `AISTUDY_INGESTION_WORKER_RECOVERY_INTERVAL_MS` | `aistudy.ingestion.worker.recovery-interval-ms` | all | `60000` | recovery tick interval |
| `AISTUDY_INGESTION_WORKER_RECOVERY_ENABLED` | `aistudy.ingestion.worker.recovery-enabled` | all | `true` | disabled in the `test` / `flyway-it` profiles so tests drive job state by hand |

Heartbeat must stay well below the stale lease: a job that outruns the lease
without renewing can be requeued while still running, which puts two workers on
the same source.

## Production required variables summary

Production must supply non-empty values for:

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `AUTH_JWT_SECRET`
- `AISTUDY_STORAGE_LOCAL_ROOT`
- `AISTUDY_CORS_ALLOWED_ORIGINS`

Production does not inherit test or local fallback values for these variables.

## First-admin bootstrap

|| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AISTUDY_BOOTSTRAP_ADMIN_ENABLED` | prod | no | `false` | boolean | no |
| `AISTUDY_BOOTSTRAP_ADMIN_USERNAME` | prod | conditional | (none) | string | no |
| `AISTUDY_BOOTSTRAP_ADMIN_PASSWORD` | prod | conditional | (none) | string | yes |

When `AISTUDY_BOOTSTRAP_ADMIN_ENABLED=true`, both username and password must be
supplied. Startup fails with `IllegalStateException` if either field is missing
or blank.

Secret posture: these are one-time provisioning credentials. After the first
successful bootstrap the runner no-op on subsequent starts, and the password is
not persisted anywhere beyond the initial account hash. Remove the bootstrap
variables from the environment after first deploy.

## AI provider (AI-001)

AI is **disabled by default**. Enabling requires a configured OpenAI-compatible
Chat Completions endpoint. The API key must be injected via environment or a
secrets platform — never committed or baked into an image.

| Variable | Profiles | Required | Default | Format / Unit | Secret? |
| --- | --- | --- | --- | --- | --- |
| `AISTUDY_AI_ENABLED` | all | no | `false` | boolean | no |
| `AISTUDY_AI_PROVIDER` | all | no | `openai-compatible` | string | no |
| `AISTUDY_AI_BASE_URL` | all | when enabled | (none) | http(s) base URL | no |
| `AISTUDY_AI_API_KEY` | all | when enabled | (none) | bearer token | yes |
| `AISTUDY_AI_MODEL` | all | when enabled | (none) | model id | no |
| `AISTUDY_AI_TEMPERATURE` | all | no | `0.3` | number | no |
| `AISTUDY_AI_MAX_OUTPUT_TOKENS` | all | no | `1024` | tokens | no |
| `AISTUDY_AI_CONNECT_TIMEOUT` | all | no | `5s` | duration | no |
| `AISTUDY_AI_READ_TIMEOUT` | all | no | `60s` | duration | no |
| `AISTUDY_AI_CONTEXT_MAX_SEARCH_RESULTS` | all | no | `8` | count | no |
| `AISTUDY_AI_CONTEXT_MAX_CHARS` | all | no | `12000` | chars | no |
| `AISTUDY_AI_CONTEXT_MAX_USER_MESSAGE_CHARS` | all | no | `8000` | chars | no |
| `AISTUDY_AI_CONTEXT_MAX_HISTORY_MESSAGES` | all | no | `20` | count | no |
| `AISTUDY_AI_CONTEXT_MAX_LEARNING_STATE_ITEMS` | all | no | `8` | count | no |
| `AISTUDY_AI_SECRET_KEY` | all | when BYOK runtime secrets are used | (none) | Base64 AES key ≥256-bit | yes |

### Runtime settings / BYOK (AI-009)

Desktop users configure **their own** provider via the Settings API. The
frontend only collects the API key; the backend stores it encrypted per user
and performs all Tutor / Explanation / Study Coach / test-connection calls.

```text
GET    /api/v1/settings/ai
PUT    /api/v1/settings/ai
POST   /api/v1/settings/ai/test-connection
DELETE /api/v1/settings/ai/api-key
```

Ownership: every operation is scoped to the authenticated JWT subject.
Users cannot read or mutate another user's settings or secret.

GET never returns the API key (only `apiKeyConfigured: true/false`).
PUT with a non-empty `apiKey` replaces **this user's** secret; omitting
`apiKey` keeps the existing secret. Placeholder masks such as `********`
are rejected.

Config resolution for each AI call (`resolveForUser(subject)`):

1. Non-secret: user runtime settings override env defaults
2. API key: user encrypted secret if a row exists; env `AISTUDY_AI_API_KEY`
   only when no user secret exists. Decrypt failure of an existing secret
   is a hard error (no env fallback).

DB never stores plaintext keys. Ciphertext format:
`Base64(IV(12) || AES-GCM ciphertext+tag)` with a unique IV per encryption.
Master key is `AISTUDY_AI_SECRET_KEY` (Base64, ≥256-bit) from the
environment/secrets platform — never committed.

Trust note: `base_url` is operator/user configuration that causes outbound
HTTP from the backend. V1 accepts http(s) absolute URLs without userinfo;
hostname allowlisting is not enforced yet (documented SSRF trust assumption).

### Vendor-neutral example (OpenAI-compatible)

Any Chat Completions endpoint that accepts OpenAI-style
`POST {baseUrl}/chat/completions` can be used via the generic adapter.
Example using StepFun:

```text
AISTUDY_AI_ENABLED=true
AISTUDY_AI_PROVIDER=openai-compatible
AISTUDY_AI_BASE_URL=https://api.stepfun.com/v1
AISTUDY_AI_API_KEY=<secret from environment/secrets platform — never commit>
AISTUDY_AI_MODEL=step-3.5-flash
```

Other OpenAI-compatible providers work the same way with their own
base URL and model id. StepFun is **not** the only supported vendor.

Request body fields sent by the adapter: `model`, `messages`,
`temperature`, `max_tokens`, `stream=false`. Response is parsed from
`choices[0].message.content`, `model`, and optional `usage.*_tokens`.
Trailing slash on base URL is normalized (`.../v1/` → `.../v1/chat/completions`).

Notes:

- Provider egress to `AISTUDY_AI_BASE_URL` is required when AI is enabled.
- First version is synchronous request/response (no SSE/WebSocket streaming).
- Live StepFun smoke (`step-3.5-flash`) verified 2026-09-13:
  `test-connection`, Tutor send-message, Study Coach, archive.
- `test-connection` uses `max_tokens=64`. Reasoning models (e.g.
  `step-3.5-flash`) consume hidden reasoning tokens before visible content;
  a tiny budget returns empty `choices[0].message.content` and maps to 502
  `AI_PROVIDER_RESPONSE_INVALID`.

Storage persistence requirement: `AISTUDY_STORAGE_LOCAL_ROOT` MUST be on
persistent storage. Database + storage are treated as one logical backup set;
losing either makes the deployment unrecoverable by backup alone.
