# .NET OLTP Load Generator — Design Spec
**Date:** 2026-09-25  
**Status:** Approved

---

## Overview

Create a .NET 8 OLTP load generator (`app2-dotnet-oltp-load-generator`) that is a 1:1 port of the existing Java OLTP load generator (`app-oltp-load-generator-new`). Both applications target the **same SQL Server database** and run simultaneously, appearing as separate services (`APP1` = Java, `APP2` = .NET) in New Relic APM.

---

## Project Structure

```
app2-dotnet-oltp-load-generator/
├── src/
│   └── LoadGen.Oltp/
│       ├── LoadGen.Oltp.csproj
│       ├── Program.cs                          (entry point + DI setup)
│       ├── OltpLoadGenerator.cs                (main load engine)
│       ├── DatabaseManager.cs                  (SqlConnection pool wrapper)
│       ├── TableCleanupService.cs              (seed data management)
│       ├── Services/
│       │   ├── CustomerService.cs
│       │   ├── OrderService.cs
│       │   ├── ProductService.cs
│       │   ├── InventoryService.cs
│       │   ├── TransactionService.cs
│       │   └── SessionService.cs
│       └── Controllers/
│           ├── CustomerController.cs
│           ├── OrderController.cs
│           ├── ProductController.cs
│           ├── InventoryController.cs
│           ├── TransactionController.cs
│           └── SessionController.cs
├── appsettings.json                            (config, mirrors application.properties)
├── newrelic.config                             (New Relic .NET agent config, mirrors newrelic.yml)
├── .env.example
├── run.sh
├── run.ps1
└── setup.sql                                   (symlink or copy — same schema as Java app)
```

---

## Framework & Dependency Mappings

| Java | .NET 8 Equivalent |
|------|-------------------|
| Spring Boot 2.7 | ASP.NET Core 8 Web API |
| Maven / `pom.xml` | `dotnet` CLI / `.csproj` |
| `application.properties` | `appsettings.json` |
| `newrelic.yml` | `newrelic.config` (XML) |
| HikariCP connection pool | `Microsoft.Data.SqlClient` (built-in ADO.NET pooling) |
| `@Trace(dispatcher=true)` | `[Transaction]` attribute (NewRelic.Api.Agent) |
| `@Trace` | `[Trace]` attribute (NewRelic.Api.Agent) |
| `NewRelic.addCustomParameter()` | `NewRelic.Api.Agent.NewRelic.AddCustomParameter()` |
| `NewRelic.noticeError()` | `NewRelic.Api.Agent.NewRelic.NoticeError()` |
| `-javaagent:newrelic.jar` | `NewRelic.Agent` NuGet package (profiler-based) |
| `@SpringBootApplication` | `Program.cs` with `WebApplication.CreateBuilder()` |
| `@RestController` | `[ApiController]` + `[Route]` |
| `@Scheduled` | `IHostedService` background timer |
| `ApplicationReadyEvent` | `IHostApplicationLifetime.ApplicationStarted` |
| Spring Actuator health endpoint | ASP.NET Core health checks (`/health`) |

### NuGet Packages

```xml
<PackageReference Include="Microsoft.Data.SqlClient" Version="5.2.0" />
<PackageReference Include="NewRelic.Agent.Api" Version="10.x" />
<PackageReference Include="NewRelic.Agent" Version="10.x" />
<PackageReference Include="Microsoft.AspNetCore.OpenApi" Version="8.0.0" />
```

---

## Database

- **Same SQL Server instance and database** as the Java app (e.g., `loadtest` or `loadtest3`)
- **No schema changes** — same `setup.sql`, same 8 tables: `CUSTOMERS`, `PRODUCTS`, `INVENTORY`, `ORDERS`, `ORDER_ITEMS`, `TRANSACTIONS`, `AUDIT_LOG`, `SESSION_DATA`
- Both apps run concurrently against the same tables
- `TableCleanupService` in .NET is disabled (Java app handles seed data refresh every 35 min to avoid conflicts between two cleanup cycles)
- Same SQL queries as Java — no ORM, raw ADO.NET (`SqlCommand`) to match Java's raw JDBC

---

## Configuration

**appsettings.json:**
```json
{
  "Database": {
    "ConnectionString": "Server=localhost,1433;Database=loadtest;User Id=sa;Password=Anitha@123456;Encrypt=false;TrustServerCertificate=true;",
    "MaxPoolSize": 10,
    "MinPoolSize": 3
  },
  "LoadGenerator": {
    "Threads": 3,
    "ApiBaseUrl": "http://localhost:8081"
  },
  "NewRelic": {
    "AppName": "APP2"
  }
}
```

All values overridable via environment variables or `.env` file.

**newrelic.config (XML):**
```xml
<configuration>
  <service licenseKey="${NEW_RELIC_LICENSE_KEY}" host="staging-collector.newrelic.com" />
  <application>
    <name>APP2</name>
  </application>
  <distributedTracing enabled="true" />
  <spanEvents enabled="true" maximumSamplesStored="10000" />
  <transactionTracer enabled="true" recordSql="obfuscated" explainEnabled="true" explainThreshold="0.01" />
  <datastoreTracer>
    <instanceReporting enabled="true" />
    <databaseNameReporting enabled="true" />
  </datastoreTracer>
  <errorCollector enabled="true">
    <ignoreStatusCodes>404</ignoreStatusCodes>
  </errorCollector>
  <applicationLogging enabled="true">
    <forwarding enabled="true" maximumSamplesStored="10000" />
    <localDecorating enabled="true" />
    <metrics enabled="true" />
  </applicationLogging>
  <labels>environment:staging;application:dotnet-oltp-load-generator;team:performance-testing;database:sqlserver</labels>
</configuration>
```

---

## Load Generator Logic

Mirrors Java exactly:

**Thread model:** `Threads` (default: 3) background `Task` workers in an infinite loop via `IHostedService`.

**Operation weights (per iteration, random):**
| Weight | Operation |
|--------|-----------|
| 30% | `CreateOrderWorkflow` |
| 25% | `UpdateCustomerWorkflow` |
| 20% | `InventoryCheckWorkflow` |
| 15% | `ProcessTransactionWorkflow` |
| 5% | `SessionManagementWorkflow` |
| 3% | `DeleteOldDataWorkflow` |
| 1% | `BulkInsertWorkflow` |
| 1% | `ProductOperationsWorkflow` |

**Timing:** 30–80ms random delay between operations. 5-second break every 2–3 minutes per thread.

**Error handling:** Exponential backoff (1s, 2s, 4s, 8s max) on consecutive errors.

**HTTP calls:** Each workflow calls local REST endpoints (`http://localhost:8081/api/...`) — same pattern as Java.

---

## New Relic APM Instrumentation

- All service methods decorated with `[Trace]`
- All workflow methods decorated with `[Transaction]`
- All controller actions decorated with `[Transaction(Web = true)]`
- Custom parameters: `customerId`, `orderId`, `productId`, `points`, `quantity`, `batchSize`, `status`, `daysToKeep`
- `NewRelic.NoticeError(exception)` on all caught exceptions
- Custom metric: `Custom/Database/ActiveConnections` (logged every 10s)

**Agent startup:** The `NewRelic.Agent` NuGet package includes the profiler. Run with:
```bash
CORECLR_ENABLE_PROFILING=1 \
CORECLR_PROFILER={36032161-FFC0-4B61-B559-F6C5D41BAE5A} \
CORECLR_PROFILER_PATH=<agent_path>/libNewRelicProfiler.dylib \
NEW_RELIC_LICENSE_KEY=xxx \
dotnet run
```
The `run.sh` and `run.ps1` scripts handle this automatically.

---

## Run Scripts

**run.sh:**
```bash
# Usage:
./run.sh           # foreground
./run.sh --bg      # background
./run.sh --stop    # kill background

# Loads .env, sets CORECLR profiler env vars, runs: dotnet run --project src/LoadGen.Oltp
```

**run.ps1:**
```powershell
# Usage:
.\run.ps1          # run with agent
.\run.ps1 -NoAgent # run without agent
.\run.ps1 -Build   # build first

# Loads .env, sets $env:CORECLR_* vars, runs dotnet
```

---

## Naming Changes from Java

| Java | .NET |
|------|------|
| `com.loadgen.oltp` namespace | `LoadGen.Oltp` namespace |
| `app1-oltp-load-generator` | `app2-oltp-load-generator` |
| `APP1` (New Relic app name) | `APP2` |
| Port `8080` | Port `8081` |
| `OltpApplication.java` | `Program.cs` |

---

## Out of Scope

- No Docker/compose setup (same as Java app — not included)
- No ORM (EntityFramework) — raw ADO.NET to match Java's raw JDBC
- `TableCleanupService` disabled in .NET (Java handles seed data)
- No schema changes to `setup.sql`
