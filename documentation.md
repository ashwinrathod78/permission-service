# Permission management service

## Scope and API behavior

The service executes MariaDB `GRANT` and `REVOKE` statements over JDBC. The REST API is:

| Method and path | Behavior |
| --- | --- |
| `POST /permissions/grant` | Applies one grant; returns `204` on success. |
| `POST /permissions/revoke` | Applies one revoke; returns `204` on success. |
| `GET /permissions/{username}` | Lists that account's table privileges from `information_schema.TABLE_PRIVILEGES`. |
| `POST /permissions/bulk` | Executes grant/revoke items concurrently and returns one status per item. |
| `GET /health` | Liveness response; deliberately independent of MariaDB. |
| `GET /ready` | Executes `SELECT 1`; returns `503` until MariaDB is reachable. |
| `GET /actuator/prometheus` | Prometheus scrape endpoint. |

Grant and revoke bodies have `username`, `database`, `table`, and `permissions`; bulk items pair an `action` (`GRANT` or `REVOKE`) with that body. Invalid requests receive `400`; a SQL rejection is returned as a generic `502` so SQL details are not exposed. Bulk responses distinguish validation, SQL, infrastructure, rejection, timeout, and cancellation outcomes. The bulk request is bounded to 100 items by default.

## SQL safety and database access

Prepared statements are used for catalog lookups, including table-existence checks and privilege reads. The privilege endpoint reads `information_schema.TABLE_PRIVILEGES` through a migration-owned `SQL SECURITY DEFINER` view restricted to the managed sample schema; the runtime account can therefore read grantee state without direct access to MariaDB system tables or global grants. MariaDB cannot bind object names in GRANT/REVOKE syntax, so identifiers and account names have narrow ASCII allow-lists, length limits, and are quoted as backtick-delimited identifiers or single-quoted accounts. Permissions are upper-cased, deduplicated, and selected from a fixed set before SQL is assembled. The service first verifies the target table exists using bound catalog parameters. It never accepts a host, SQL fragment, wildcard permission, or arbitrary grant option from the API.

Flyway creates a sample schema, table and non-login demonstration principal. It also provisions the configured service account with grant authority scoped to that sample schema for local use. Migration credentials and the runtime credential are separate configuration inputs (`MIGRATION_DB_*` and `DB_*`); production operators should provision a dedicated least-privileged account and migration identity rather than reuse a DBA. Passwords are environment/configuration inputs, not source constants or Docker build arguments. Supply sensitive values through the deployment's secret facility.

The example migration interpolates the configured service-account identifier and password into SQL because MariaDB account DDL has no bind-parameter form. Keep the account identifier to a conventional SQL account name, and provide a SQL-literal-safe password for this demo migration. For production, pre-provision the runtime account out of band and omit account creation from the migration identity.

## Concurrency, bounds, and shutdown

Bulk work uses a fixed four-thread executor and a bounded 32-entry queue (36 in-flight/queued submissions per service instance); larger batches are still processed item-by-item, with excess submissions reported as `rejected`. The JDBC pool is capped at five connections: one connection per bulk worker plus one reserved for readiness, reads, and other request work. Startup rejects pool settings that leave no reserved connection. Size both limits against MariaDB `max_connections` across all replicas, leaving room for migrations, operators, monitoring, and failover.

Each username maps to a lock stripe, so writes for one principal within an instance cannot overlap; unrelated principals can run in parallel. A fixed striped lock table bounds memory at the cost of occasionally serializing unrelated accounts. MariaDB remains the cross-instance authority; distributed deployments must assume database locking, not this in-process lock, is the final arbiter. Bulk outcomes are independent: one failed item does not roll back successful siblings.

The bulk deadline defaults to 10 seconds. Expired or request-interrupted work is cancelled with `Future.cancel(true)` and represented per item; JDBC query timeouts and pool connection timeouts bound database waits. Cancellation is best-effort once a driver has sent a GRANT/REVOKE: a connection failure or timeout can leave the caller uncertain whether MariaDB committed the statement. On shutdown the executor stops accepting work, drains for 15 seconds, then interrupts remaining work and waits up to five more seconds. The HTTP server uses graceful shutdown. Do not treat cancellation or an ambiguous connection error as proof the privilege change did not happen: read the resulting privilege state before retrying.

## Configuration and diagnostics

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, pool sizing, bulk concurrency, and deadlines are externalized. HikariCP's maximum size and wait timeout are finite. Every HTTP request receives a validated `X-Correlation-ID` (or a generated UUID); that ID is returned in the response and included in JSON logs. Bulk workers propagate it to their SQL log entries. SQL text contains only validated identifiers, account names, and allow-listed permissions; credentials are not logged.

Actuator exposes request timers, JVM/GC/thread metrics, and Hikari pool metrics. Custom metrics report permission outcomes, validation/SQL/infrastructure failures, database round-trip histograms (including the 500 ms service-level bucket), and bulk executor active/queue/rejection values. The two provisioned Grafana dashboards cover API health and a troubleshooting path from pool pressure and slow SQL to JVM and executor saturation. The Prometheus `PermissionServiceUnavailable` rule fires after two continuous minutes without a scrape. This allows a short restart or deploy interruption but detects sustained loss; production alert routing and a separate database/latency SLO alert should be added.

## Build, image, and local proof

The project retains Gradle. Run `.\gradlew.bat fullPipeline` (Windows) or `./gradlew fullPipeline` (Linux/macOS) from the project root. The task runs compilation and unit tests, Testcontainers/Failsafe-style integration tests, builds the version-tagged Docker image, scans it with Trivy, starts MariaDB/service/Prometheus/Grafana, exercises `/ready` and `/health`, and tears the stack and its volume down in a `finally` block. Docker and Trivy must be installed and running; image registry access is needed on the first run. Integration tests require Docker and will fail rather than substitute a mock database.

The Dockerfile has a digest-pinned JDK artifact stage and a digest-pinned Alpine JRE runtime stage. The runtime copies only the executable jar and a tiny readiness checker, runs as UID/GID 10001, and uses a memory-aware JVM setting. Dependencies and application bytes live in their own final layer; the stable JRE base stays cacheable across versions. The tested x86-64 image reports 372,079,166 bytes (about 355 MiB uncompressed) via `docker image inspect`; the executable jar is about 35 MB and most of the remaining size is the upstream Temurin JRE. Registry transfer size is smaller due to compressed layers. The one-command Gradle pipeline mirrors the requested Maven lifecycle without replacing the project's established Gradle build. A digest-pinned Trivy container scans the exact Docker image archive, so a separate scanner installation is unnecessary. The gate fails on `CRITICAL` findings, including unfixed findings; no findings are suppressed. The initial scan identified three fixed Tomcat CVEs in the Spring Boot-managed patch, so the build explicitly aligns all embedded Tomcat modules to 11.0.26; the resulting scan reported zero critical findings. The intentional-failure task reported critical CVE-2021-36159 in `apk-tools` for `alpine:3.10` (fixed upstream in 2.10.7-r0). To reproduce it, run `.\gradlew.bat scanImageIntentionalFailure`; the task passes only when Trivy rejects that deliberately vulnerable image. Review current scanner output and dependency advisories on every release; do not convert a finding into a suppression without a tracked owner, rationale, and expiry.

The local Compose stack requires Docker and externally supplied `MARIADB_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD`, `MIGRATION_DB_USERNAME`, `MIGRATION_DB_PASSWORD`, and `GRAFANA_ADMIN_PASSWORD`. `fullPipeline` generates throwaway values for its ephemeral stack; persistent deployments must source them from a secret manager. Grafana's anonymous viewer is enabled only for the local demo. The REST API has no built-in identity provider or authorization policy in this exercise: deploy it only on a private trusted network behind an authenticated, audited gateway (or add service-native OIDC/mTLS and tenant-scoped authorization) before production use.

## Non-functional design targets

Initial sizing targets (to be confirmed with workload measurements):

| Measure | Starting target |
| --- | --- |
| Sustained / peak API load | 20 / 60 requests per second per deployment; writes capped by MariaDB lock/DDL throughput. |
| Read latency | p95 under 100 ms for privilege reads at the service, excluding client/network distance. |
| Grant/revoke latency | p95 under 500 ms; bulk completes within the 10-second request deadline. |
| Availability | 99.9% monthly (about 43.8 minutes monthly error budget); report database-caused unavailability separately. |
| Recovery | RPO at most 5 minutes; RTO at most 60 minutes, subject to replica/backup restore tests. |
| Initial data envelope | 100 tenants, 100,000 principals, 10,000 schemas/tables per tenant, and 100 table privileges per principal. |

These are design assumptions, not load-test results or current guarantees. Validate the throughput and tail latency with realistic MariaDB grants, pool contention, and failover drills before adopting them as an SLA.

### Capacity and scaling limits

The first bottleneck is expected to be database connection capacity and serialized DDL/privilege metadata work, not stateless web CPU. With a five-connection pool, never schedule instances so that `replicas × pool-size` approaches the database `max_connections`; reserve connections for migration, administration, monitoring, and failover. Use an explicit fleet-wide connection budget and reduce per-pod pools as replicas scale. Bounded queues and request deadlines provide backpressure instead of unbounded memory growth. At sustained overload, return per-item rejection rather than silently queueing indefinitely.

The synchronous bulk API is appropriate for bounded, interactive requests. If measured workloads exceed request deadlines, move bulk intent behind a durable queue: require a caller-supplied idempotency key, persist the request and item state, deduplicate key reuse, expose status/results, and define retry and dead-letter policy. GRANT and REVOKE are often state-idempotent, but a retry after an ambiguous failure can race with later policy changes; therefore reconcile the desired state and sequence/version before replay. Per-tenant quotas and rate limits should sit at the authenticated edge and be enforced consistently across replicas. Reads can be cached briefly or served from a replica only with an explicit staleness bound; immediately-after-write reads should use the primary or a read-your-writes mechanism.

### Availability and failover

Run at least two service instances across independent failure domains behind a readiness-aware load balancer. Rolling or blue/green rollout should wait for database-backed readiness, drain connections and requests on termination, and keep migration authority separate from application credentials. The database needs synchronous HA within the primary failure domain or a primary/replica arrangement with monitored, automated failover. Replicas do not remove write serialization. During primary loss, an in-flight grant may have committed even if the client received a timeout. Do not blindly replay on a new primary: query actual privileges and compare with the desired policy before reconciliation.

Managed database offerings can supply automated failover, backups, patching, and point-in-time restore; managed identity/secret and metrics services can supply rotation, access auditing, storage, and alert delivery. In a self-managed or air-gapped estate, replace those capabilities with an operated MariaDB HA cluster, encrypted off-host backup/PITR tooling, a hardware-backed or tightly controlled secret store, and the Prometheus/Grafana/alert-routing stack. Keep the service's interfaces and recovery procedures independent of those implementation choices.

### Backup and disaster recovery

Take encrypted, access-controlled backups and enable binary-log point-in-time recovery to the selected RPO. Replicate backups and logs to a second site far enough away to survive a regional event, with replication lag monitored against the RPO. Rehearse full restore and service cutover at least quarterly and after major engine/backup changes; record measured RTO rather than assuming it.

Restoring database grants alone can roll policy back in time. The long-term source of truth should be an independently versioned desired-policy store or signed policy repository, not only MariaDB's current grants. After restore, enumerate actual grants from `information_schema`, compare them with desired policy, produce an auditable drift report, and reconcile through reviewed/idempotent grant operations. Preserve request IDs, principal, target, requested policy version, outcome, and reconciliation history in durable audit storage outside the restored database.
