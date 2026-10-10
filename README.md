# Permission Management Service

A Spring Boot REST service for granting, revoking, and inspecting MariaDB table privileges. It executes permission changes over JDBC, validates SQL identifiers before building `GRANT`/`REVOKE` statements, and supports bounded concurrent bulk operations with per-item results.

> **Security:** The API does not provide authentication or authorization. Run it only on a trusted network behind an authenticated, audited gateway. The local Compose credentials are for development only.

## Contents

- [Architecture](#architecture)
- [Technology stack](#technology-stack)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start)
- [API](#api)
- [Tests and full pipeline](#tests-and-full-pipeline)
- [Monitoring](#monitoring)
- [Configuration](#configuration)
- [Project layout](#project-layout)
- [Documentation](#documentation)

## Architecture

### High-level

```text
Client / API consumer
        |
        | HTTP + JSON; X-Correlation-ID
        v
Permission Service (Spring Boot)
  |             |               |
  | REST API    | bounded bulk  | metrics / JSON logs
  | validation  | executor      |
  v             v               v
MariaDB <--- JDBC / HikariCP   Prometheus ---> Grafana
  |
  +-- Flyway-managed sample schema, account, and privilege view
```

The service is stateless. MariaDB is the source for current effective grants; durable desired policy and cross-instance authorization are intentionally outside this sample service. The local observability stack provisions Prometheus scraping, two Grafana dashboards, and a Prometheus alert rule.

### Request and data flow

1. A request reaches the REST controller. The correlation filter validates or generates `X-Correlation-ID`, returns it in the response header, and places it in the logging context.
2. Request validation checks required fields and bounds. The service independently validates account and object names against narrow allow-lists and accepts only known privileges.
3. Grant/revoke operations verify the target table using bound SQL parameters, serialize writes for the same account within the instance, then execute a quoted SQL statement through JDBC.
4. Privilege reads use a parameterized query against a migration-created view over MariaDB's table privilege metadata.
5. Bulk operations use a fixed-size executor and bounded queue. Each item reports its own success, validation/database failure, rejection, timeout, or cancellation result. JDBC pool capacity is sized to leave a connection for non-bulk requests.
6. Micrometer/Actuator expose request, database, JVM, pool, and bulk-executor metrics for Prometheus. Structured logs include correlation IDs.

Bulk cancellation is best-effort: when a database operation times out or connectivity is lost, the caller may not know whether MariaDB applied the grant. Read the resulting privilege state before retrying.

## Technology stack

| Area | Technology |
| --- | --- |
| Language/runtime | Java 25 |
| Application/build | Spring Boot 4, Gradle |
| HTTP/API docs | Spring MVC, springdoc-openapi, Swagger UI |
| Database access | MariaDB Connector/J, Spring JDBC, HikariCP |
| Schema management | Flyway |
| Tests | JUnit, Spring test support, Testcontainers MariaDB |
| Metrics/logging | Micrometer, Prometheus, Logback JSON encoder, Grafana |
| Container/security gate | Multi-stage Dockerfile, non-root runtime, digest-pinned images, Trivy |

## Prerequisites

- JDK 25.
- Docker Desktop (Docker Engine and Compose v2) for local MariaDB, integration tests, container builds, scans, and the full pipeline.
- Network access for Gradle dependencies and the initial container image and vulnerability database downloads.
- Ports `3306` (MariaDB), `8080` (API), `9090` (Prometheus), and `3000` (Grafana) available for the corresponding local services.

MariaDB does not need to be installed on the host when using the Docker-backed local setup. See [usage-document.md](doc/usage-document.md) for using a separately installed local database.

## Quick start

Run from the repository/workspace root. On Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

On Linux/macOS:

```sh
./gradlew bootRun
```

With Docker running and no Spring profile explicitly selected, the default `local` profile starts the development MariaDB defined in `compose.dev.yaml`. Flyway applies the sample migration at startup. Keep the application working directory at the repository root when launching from an IDE. To stop the app, stop its Run process; Spring Boot manages the local Compose lifecycle.

Check liveness and database readiness:

```text
GET http://localhost:8080/health
GET http://localhost:8080/ready
```

Interactive API documentation:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

For manual MariaDB setup, Compose operation, examples, and troubleshooting, see [usage-document.md](doc/usage-document.md).

## API

Base URL: `http://localhost:8080`. Requests use JSON and may include `X-Correlation-ID` (1-64 characters from `[A-Za-z0-9._-]`). If absent or invalid, the service generates an ID.

| Method | Endpoint | Description | Successful response |
| --- | --- | --- | --- |
| `POST` | `/permissions/grant` | Grant table privileges | `204 No Content` |
| `POST` | `/permissions/revoke` | Revoke table privileges | `204 No Content` |
| `GET` | `/permissions/{username}` | List table privileges | `200 OK`, JSON array |
| `POST` | `/permissions/bulk` | Apply concurrent grant/revoke operations | `200 OK`, per-item results |
| `GET` | `/health` | Process liveness | `200 OK` |
| `GET` | `/ready` | Database-backed readiness (`SELECT 1`) | `200 OK`, or `503` if unavailable |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape | `200 OK`, Prometheus text |

Grant and revoke request:

```json
{
  "username": "test-reader",
  "database": "permission_sample",
  "table": "test_table",
  "permissions": ["SELECT", "INSERT"]
}
```

Bulk request:

```json
{
  "operations": [
    {
      "action": "GRANT",
      "request": {
        "username": "test-reader",
        "database": "permission_sample",
        "table": "test_table",
        "permissions": ["UPDATE"]
      }
    },
    {
      "action": "REVOKE",
      "request": {
        "username": "test-reader",
        "database": "permission_sample",
        "table": "test_table",
        "permissions": ["INSERT"]
      }
    }
  ]
}
```

Privilege values are restricted to `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `DROP`, `ALTER`, `INDEX`, and `REFERENCES`. Database and table identifiers must be valid and refer to an existing table. The default maximum bulk size is 100 operations. Bulk `200 OK` does not imply every item succeeded; inspect every item's `status`.

Typical errors include `400 Bad Request` for invalid input and `502 Bad Gateway` if MariaDB rejects a single grant/revoke request. Bulk errors are returned item-by-item. Full request/response examples are in the [usage guide](doc/usage-document.md#api-reference-and-examples).

## Tests and full pipeline

Commands below are run from the workspace root using the included wrapper.

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest
.\gradlew.bat check
```

Linux/macOS:

```sh
./gradlew test
./gradlew integrationTest
./gradlew check
```

The integration tests run against real MariaDB through Testcontainers and require Docker. `check` runs both unit and integration tests.

Run the complete build-to-smoke-test pipeline:

```powershell
# Windows
.\gradlew.bat fullPipeline
```

```sh
# Linux/macOS
./gradlew fullPipeline
```

The pipeline compiles and tests, builds the version-tagged Docker image, scans it with Trivy (failing on CRITICAL vulnerabilities), starts the service with MariaDB, Prometheus, and Grafana, exercises live API/metrics smoke tests, then tears the stack and its data volume down. Docker must be running and the documented ports must be available. Scanner findings are not suppressed.

To demonstrate the CVE gate against a deliberately vulnerable image:

```powershell
.\gradlew.bat scanImageIntentionalFailure
```

This is a separate demonstration task; a detected vulnerability is expected.

## Monitoring

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3000>
- API metrics: <http://localhost:8080/actuator/prometheus>

Grafana provisions **Service health** and **Troubleshooting** dashboards. The troubleshooting dashboard includes connection-pool, database, JVM, thread, and bulk-executor signals. Prometheus loads an alert rule from `observability/prometheus/alerts.yml`. The local Grafana stack enables anonymous viewer access for convenience; do not expose it as configured to an untrusted network.

## Configuration

Configuration is supplied through environment variables or Spring properties. Important variables include:

| Variable | Purpose | Local default |
| --- | --- | --- |
| `SERVER_PORT` | HTTP port | `8080` |
| `DB_URL` | Runtime JDBC URL | `jdbc:mariadb://localhost:3306/permission_sample` |
| `DB_USERNAME` / `DB_PASSWORD` | Runtime database identity | Local demo values |
| `MIGRATION_DB_URL` | Flyway JDBC URL | `jdbc:mariadb://localhost:3306/permission_metadata` |
| `MIGRATION_DB_USERNAME` / `MIGRATION_DB_PASSWORD` | Flyway migration identity | Local demo root identity |
| `DB_POOL_MAX_SIZE` | Maximum JDBC pool size | `5` |
| `BULK_THREADS` | Bulk worker count | `4` |
| `BULK_QUEUE_CAPACITY` | Bounded bulk task queue size | `32` |
| `BULK_MAX_ITEMS` | Maximum operations in one batch | `100` |
| `BULK_TIMEOUT` | Bulk request deadline | `10s` |

Some compose-only credentials (MariaDB root and Grafana administrator) are required by `compose.yaml` and supplied by the full pipeline for its disposable environment. Use a secret manager or protected deployment configuration for real deployments; never commit credentials. Review [documentation.md](doc/documentation.md) for architecture, concurrency, security, capacity, availability, and disaster-recovery details.

## Project layout

```text
.
├── README.md
├── usage-document.md
├── build.gradle                 # Application, tests, image and pipeline
├── settings.gradle
├── Dockerfile
├── compose.yaml                 # Full app + MariaDB + monitoring stack
├── compose.dev.yaml             # Local MariaDB for IDE / bootRun
├── observability/               # Prometheus, alert, Grafana provisioning
└── src/
    ├── main/java/               # API, service, and configuration layers
    ├── main/resources/          # Application config and Flyway migrations
    ├── test/java/               # Unit tests
    └── integrationTest/         # Testcontainers MariaDB tests
```

## Documentation

- [Usage guide](doc/usage-document.md) — setup, API request/response examples, Swagger, tests, full pipeline, and local database options.
- [Architecture and NFR document](doc/documentation.md) — high/low-level design, concurrency, scaling, HA, and disaster recovery.
- [Original requirements](doc/requirement.txt) — source project brief.
