# Permission service usage guide

The repository root is the single Gradle project root. Run Gradle commands from this directory using `.\gradlew.bat` on Windows or `./gradlew` on Linux/macOS.

When launched with no explicitly selected Spring profile, the application uses the `local` profile and Spring Boot Docker Compose support starts `compose.dev.yaml` automatically. This provides a local MariaDB for IntelliJ's Run action as well as for Gradle `bootRun`. The local Compose database connects as MariaDB root because its migrations create databases and accounts. The local-only credentials and data volume are strictly for development and must not be used in production.

## Prerequisites

- JDK 25 (the build uses the Java 25 toolchain).
- Gradle wrapper (included); a separate Gradle installation is optional.
- Docker Desktop with Docker Compose v2 and a running Docker engine for Testcontainers, image builds/scans, and the full stack.
- Internet access for Gradle dependency resolution and initial Docker image/Trivy database downloads.
- Ports `3306`, `8080`, `3000`, and `9090` available when using the Compose stack.

You do not need to install MariaDB separately if you use Docker. The local Compose service maps MariaDB to `localhost:3306`, initializes the `permission_metadata` database, and the application migration creates the sample database, table, account, and view. If MariaDB is already running on port 3306, stop it or configure another port before starting the app. To connect to a separately installed MariaDB, it must be reachable at `localhost:3306`, support the MariaDB Connector/J driver, and allow the migration account to create the sample database, table, account, and view. The default app URL is `jdbc:mariadb://localhost:3306/permission_sample`; Flyway defaults to `jdbc:mariadb://localhost:3306/permission_metadata`.

## Build and run tests

Run these PowerShell commands from:

```powershell
Set-Location 'C:\Users\ASHWIN RATHOD\Ashwin\projects\permission-service'
```

Unit tests:

```powershell
gradle.bat test
```

The same command using the bundled wrapper is:

```powershell
.\gradlew.bat test
```

Real-MariaDB integration tests use Testcontainers and therefore require Docker to be running:

```powershell
gradle.bat integrationTest
```

Run all verification tasks (unit tests plus integration tests):

```powershell
gradle.bat check
```

All commands are run from the repository root; there is no nested Gradle project.

## Run the service in IntelliJ or against a local MariaDB

After refreshing the Gradle project in IntelliJ, use the `PermissionServiceApplication` Run configuration. Keep the working directory at the workspace root (`C:\Users\ASHWIN RATHOD\Ashwin\projects\permission-service`) and do not set a Spring profile; the default `local` profile and Spring Boot Compose integration will start the MariaDB container automatically. Spring Boot stops the Compose-managed container when the app exits. The first run downloads the MariaDB image.

Alternatively, start a disposable database manually from the workspace root. Disable automatic Compose startup first to avoid trying to bind port 3306 twice:

```powershell
$env:SPRING_DOCKER_COMPOSE_ENABLED = 'false'
docker run --name permission-mariadb `
  -e MARIADB_ROOT_PASSWORD='local-root-password' `
  -e MARIADB_DATABASE=permission_metadata `
  -p 3306:3306 `
  -d mariadb:11.4.5
```

Wait until MariaDB is accepting connections:

```powershell
docker exec permission-mariadb healthcheck.sh --connect --innodb_initialized
```

Set the application and migration credentials in the same PowerShell window. Use a demo password only on a local development machine:

```powershell
$env:DB_URL = 'jdbc:mariadb://localhost:3306/permission_sample'
$env:DB_USERNAME = 'permission_service'
$env:DB_PASSWORD = 'local-service-password'
$env:MIGRATION_DB_URL = 'jdbc:mariadb://localhost:3306/permission_metadata'
$env:MIGRATION_DB_USERNAME = 'root'
$env:MIGRATION_DB_PASSWORD = 'local-root-password'
```

Run the service from the workspace root:

```powershell
.\gradlew.bat bootRun
```

Flyway applies the migration, creates `permission_sample.test_table` and the `test-reader` account, creates the configured runtime account, and grants that account the schema-scoped authority required by this demo. The migration identity needs administrative permission to create the view and account. For production, use separately provisioned least-privilege application and migration identities; do not use the local root-account setup.

To stop and remove the manually-started database container after stopping the app:

```powershell
docker stop permission-mariadb
docker rm permission-mariadb
```

## API reference and examples

Base URL: `http://localhost:8080`. JSON requests should use `Content-Type: application/json`. The service returns an `X-Correlation-ID` header on requests; clients can send their own 1-64 character ID matching `[A-Za-z0-9._-]` to trace a request in logs.

The allowed table privileges are `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `DROP`, `ALTER`, `INDEX`, and `REFERENCES`. Account names, database names, and table names are restricted to simple safe identifiers; database and table must already exist. The seeded target is `permission_sample.test_table`.

### Grant permissions

```http
POST /permissions/grant
Content-Type: application/json
```

```json
{
  "username": "test-reader",
  "database": "permission_sample",
  "table": "test_table",
  "permissions": ["SELECT", "INSERT"]
}
```

Expected success: `204 No Content` with an empty response body. The operation applies both privileges to the account.

### Revoke permissions

```http
POST /permissions/revoke
Content-Type: application/json
```

```json
{
  "username": "test-reader",
  "database": "permission_sample",
  "table": "test_table",
  "permissions": ["INSERT"]
}
```

Expected success: `204 No Content` with an empty response body.

### Read privileges

```http
GET /permissions/test-reader
```

Expected success: `200 OK` and a JSON array. Immediately after the initial migration, the seeded `test-reader` has `SELECT` on `permission_sample.test_table`; additional grants/revokes are reflected in subsequent reads.

```json
[
  {
    "username": "test-reader",
    "database": "permission_sample",
    "table": "test_table",
    "privilege": "SELECT",
    "grantable": false
  }
]
```

An account with no matching table privileges receives `200 OK` with `[]`.

### Apply a bulk of grant/revoke operations

```http
POST /permissions/bulk
Content-Type: application/json
```

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

Expected success: `200 OK`, with an independent result for every submitted item. A batch can partially succeed; check each item status rather than treating an HTTP 200 as success for every operation.

```json
{
  "results": [
    {
      "index": 0,
      "action": "GRANT",
      "username": "test-reader",
      "status": "succeeded",
      "message": "Permission change applied."
    },
    {
      "index": 1,
      "action": "REVOKE",
      "username": "test-reader",
      "status": "succeeded",
      "message": "Permission change applied."
    }
  ],
  "succeeded": 2,
  "failed": 0,
  "timedOut": 0,
  "cancelled": 0
}
```

Item statuses include `succeeded`, `validation_error`, `sql_error`, `infrastructure_error`, `rejected`, `timed_out`, and `cancelled`. The default maximum batch size is 100; executor thread count, queue size, maximum item count, and deadline are configurable using `BULK_THREADS`, `BULK_QUEUE_CAPACITY`, `BULK_MAX_ITEMS`, and `BULK_TIMEOUT`.

### Liveness, readiness, and monitoring

| Method/path | Success response | Meaning |
| --- | --- | --- |
| `GET /health` | `200 {"status":"UP"}` | Process is alive; does not require MariaDB. |
| `GET /ready` | `200 {"status":"UP"}` | Database-backed `SELECT 1` succeeded; otherwise responds `503 Service Unavailable`. |
| `GET /actuator/prometheus` | `200` Prometheus text format | Metrics scrape endpoint. |

### Error responses

Invalid JSON fields, unsafe identifiers, unsupported privileges, missing target tables, and oversized batches return `400 Bad Request`. Example:

```json
{
  "error": "validation",
  "message": "Database and table must be valid MariaDB identifiers."
}
```

A database rejection on a single grant/revoke request returns `502 Bad Gateway`; database details are intentionally not returned:

```json
{
  "error": "sql",
  "message": "The database rejected the request."
}
```

## Swagger / OpenAPI

When the service is running, open the interactive Swagger UI at:

```text
http://localhost:8080/swagger-ui.html
```

The raw OpenAPI v3 document is available at:

```text
http://localhost:8080/v3/api-docs
```

Swagger documents the permission, bulk, health, and readiness operations and their main success/error responses. Use the UI's **Try it out** feature against the local service. It does not bypass request validation or create database accounts. These endpoints are not authenticated by this service; do not expose Swagger or the API on an untrusted network.

## Run the full pipeline

Run from the workspace root:

```powershell
gradle.bat fullPipeline
```

Or, using the checked-in wrapper:

```powershell
.\gradlew.bat fullPipeline
```

The pipeline runs unit tests and real MariaDB Testcontainers integration tests; builds the version-tagged Docker image; scans it with Trivy and fails on CRITICAL vulnerabilities; starts MariaDB, the service, Prometheus, and Grafana; smoke-tests readiness, liveness, a real grant/read/revoke cycle, and Prometheus metrics; then tears down the containers and database volume, including after a failure.

Docker Desktop must be running and the required ports must be free. First runs download dependencies, service/base images, and the Trivy database and can take several minutes. The pipeline supplies ephemeral credentials for its disposable stack. Do not set production passwords merely to run it.

The intentional CVE-gate demonstration is separate from the normal pipeline:

```powershell
.\gradlew.bat scanImageIntentionalFailure
```

It pulls a deliberately vulnerable Alpine image and verifies the scanner rejects it; this is expected demonstration behavior, not the regular service image scan.

## Compose stack and observability

The full pipeline starts and removes the entire stack automatically. To start the Compose stack manually, first build and scan the image:

```powershell
.\gradlew.bat dockerImage
.\gradlew.bat scanImage
```

Set stack credentials in the PowerShell session (replace the examples locally), then run Compose from the repository root:

```powershell
$env:PERMISSION_SERVICE_IMAGE = 'permission-service:0.0.1-SNAPSHOT'
$env:MARIADB_ROOT_PASSWORD = 'local-root-password'
$env:DB_USERNAME = 'permission_service'
$env:DB_PASSWORD = 'local-service-password'
$env:MIGRATION_DB_USERNAME = 'root'
$env:MIGRATION_DB_PASSWORD = $env:MARIADB_ROOT_PASSWORD
$env:GRAFANA_ADMIN_PASSWORD = 'local-grafana-password'
docker compose up --detach --wait
```

The local endpoints are service `http://localhost:8080`, Prometheus `http://localhost:9090`, and Grafana `http://localhost:3000` (admin username `admin`; the password is the value of `GRAFANA_ADMIN_PASSWORD`). Grafana provisions the service-health and troubleshooting dashboards and Prometheus data source from files. The local demo enables anonymous Grafana viewer access.

Tear the manual stack down, including its MariaDB data volume, with:

```powershell
docker compose down --volumes --remove-orphans
```

## Configuration and security notes

Database URL, usernames, passwords, pool bounds, server port, and bulk executor settings are configurable through environment variables. Credentials should come from a secret manager or protected environment configuration; never commit them. The REST API has no built-in authentication, authorization, tenant isolation, or user-account provisioning. Deploy it only behind an authenticated and audited gateway on a trusted network, and provision/rotate database identities using production-specific least-privilege policy.
