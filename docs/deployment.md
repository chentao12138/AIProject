# Deployment Guide

## Architecture assumptions

Production runs three logical components:

- AIStudy backend service (Spring Boot jar or Docker image)
- MySQL 8.4 database
- Persistent asset storage volume

The backend stores only `storageKey` paths in MySQL; the actual bytes live
in the configured local storage root. Database rows and storage bytes form
one logical backup set.

## Java / image

The service targets Java 21. The published Docker image uses
`eclipse-temurin:21-jre` and runs as non-root `aistudy:1001`. The
multi-stage builder uses `eclipse-temurin:21-jdk` so Maven Wrapper can
compile and package the fat jar.

## Required production environment variables

|| Variable | Required | Secret? |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes | no |
| `DB_URL` | yes | no |
| `DB_USERNAME` | yes | no |
| `DB_PASSWORD` | yes | yes |
| `AUTH_JWT_SECRET` | yes | yes |
| `AISTUDY_STORAGE_LOCAL_ROOT` | yes | no |
| `AISTUDY_CORS_ALLOWED_ORIGINS` | yes | no |
| `AISTUDY_BOOTSTRAP_ADMIN_ENABLED` | conditional | no |
| `AISTUDY_BOOTSTRAP_ADMIN_USERNAME` | conditional | no |
| `AISTUDY_BOOTSTRAP_ADMIN_PASSWORD` | conditional | yes |

Variables marked secret must be injected through a secrets manager or
protected environment mechanism; they must not appear in logs, docs, or
image layers.

## First-admin bootstrap

Set `AISTUDY_BOOTSTRAP_ADMIN_ENABLED=true` only for the first successful
start when no ADMIN exists.

- enabled=true + valid fields + no ADMIN -> create account with USER+ADMIN.
- enabled=true + ADMIN already exists -> no-op; existing ADMIN is never touched.
- enabled=true + missing fields -> startup fails with `IllegalStateException`.
- enabled=false -> no-op.

Plaintext passwords are never logged, and bootstrap credentials should be
removed from the environment after first deploy.

## Database / Flyway startup

Flyway runs automatically on startup in the `prod` profile. Migrations are
forward-only; `clean-disabled` is true in production.

Before any destructive operation (manual schema change, restore from backup),
take a fresh backup. The project does not use undo migrations.

## Persistent asset storage

Configure `AISTUDY_STORAGE_LOCAL_ROOT` on persistent block storage that is
backed up together with the database. Losing storage after a DB restore
leaves broken references; losing the DB after a storage restore leaves
orphaned bytes.

Example path inside a container or VM:

- `/var/lib/aistudy/resources`

The Docker image creates this directory and chowns it to the runtime user,
but the volume mount must be provided by the deployment.

## Ports

The service binds to `0.0.0.0:8080` by default. Production traffic should
arrive through a reverse proxy that terminates TLS; the backend port does
not need to be exposed on the host when a proxy is present.

## Reverse proxy / TLS

Production must sit behind a trusted reverse proxy. The profile enables

- `server.forward-headers-strategy=framework`
- `ForwardedHeaderFilter`

These only honour `X-Forwarded-*` headers from the trusted upstream. Pair
with network policy / firewall rules that prevent direct access to the
backend port from untrusted networks.

## CORS

`AISTUDY_CORS_ALLOWED_ORIGINS` must be explicit origins in production.
`*` is never used. Leave the property empty when no cross-origin browser
clients are expected.

## Health / readiness / liveness

- `/actuator/health` combined summary
- `/actuator/health/liveness` process alive; must not depend on DB/storage
- `/actuator/health/readiness` can this instance serve traffic; depends on DB and storage root

Health is anonymous. Readiness/liveness report status only; they do not
expose physical paths or secrets.

## Actuator / admin exposure

Default exposure: `health`, `info`, `prometheus`.

Sensitive endpoints (`env`, `configprops`, `beans`, `heapdump`,
`threaddump`, `mappings`, `loggers`, `shutdown`) remain disabled.

`/actuator/**` other than health requires ADMIN and a valid JWT. Network
policy must still restrict scrape access to prometheus; actuator security
is not a substitute for transport-layer restriction.

## Graceful shutdown

`server.shutdown=graceful` with `spring.lifecycle.timeout-per-shutdown-phase=30s`
lets active requests complete before the context closes. Pair reverse-proxy
timeouts and retry budgets with this value so draining connections do not
surface as client-facing 502s.

## Logging

The application logs to stdout using a structured pattern that includes
`requestId=%X{requestId:-}`. No file appenders are configured by default.
Operators should capture stdout through the container or service supervisor.

## Storage reconciliation policy

`POST /api/v1/admin/operations/storage/reconcile` is the only supported
reconciliation surface.

Default mode is `DRY_RUN`. `CLEANUP` is an explicit, authenticated, audited
admin action. There is no scheduled destructive cleanup and no automatic
startup cleanup.

Cleanup respects `aistudy.operations.storage-reconciliation.min-age`
(default `PT1H`) so recently uploaded assets are never orphaned by a race
between upload and reconcile.

## Backup expectations

Database and storage are one logical backup set. Expected coverage:

- MySQL logical backup
- Storage root directory copy / snapshot
- Key deployment configuration (without plaintext secrets)

Test restore procedures before relying on them in production.

## Upgrade / rollback

- Flyway migrations are forward-only.
- Run migrations only after a verified backup exists.
- Do not roll back by re-running old migrations; restore from backup if
  the new schema is unacceptable.
- Backend image upgrades should be tested against a cloned DB + storage
  pair before promotion.

## Rate limiting

Rate limiting belongs at the reverse proxy or host firewall. The backend
does not enforce per-IP or per-user rate limits in this phase.

## Verification

Final runtime verification commands are deferred. Expected checks include:

- focused backend tests
- full clean backend tests
- packaged image start with prod profile
- health/readiness/liveness responses
- first-admin bootstrap flow
- cleanup reconcile dry-run against real storage
