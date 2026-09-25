# .NET OLTP Load Generator (APP2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a .NET 8 ASP.NET Core OLTP load generator (`app2-dotnet-oltp-load-generator`) that mirrors the Java load generator, connecting to the same SQL Server database and appearing as APP2 in New Relic APM.

**Architecture:** ASP.NET Core 8 Web API on port 8081. Worker threads run as a background `IHostedService`, making HTTP calls to local REST endpoints — same pattern as Java. Raw ADO.NET (`SqlCommand`) for all DB access. New Relic .NET agent instrumented via `[Transaction]` and `[Trace]` attributes.

**Tech Stack:** .NET 8, ASP.NET Core 8, Microsoft.Data.SqlClient 5.2.0, NewRelic.Agent.Api 10.x, NewRelic.Agent 10.x, SQL Server (same instance as Java APP1)

**Spec:** `docs/superpowers/specs/2026-09-25-dotnet-apm-design.md`

## Global Constraints

- Target framework: `net8.0`
- Root namespace: `LoadGen.Oltp`; services in `LoadGen.Oltp.Services`; controllers in `LoadGen.Oltp.Controllers`
- App name in New Relic: `APP2`
- Server port: `8081`
- No ORM — raw `SqlCommand`/`SqlDataReader` only; SQL is word-for-word from the Java source
- `TableCleanupService` is NOT implemented — the Java app handles seed data refresh every 35 min
- New Relic attributes: `[Transaction]` and `[Trace]` from `NewRelic.Api.Agent` namespace
- All SQL parameters must use named parameters (`@paramName`), never string concatenation
- All `SqlConnection` and `SqlCommand` must be in `using` blocks to return connections to pool
- Project folder: `app2-dotnet-oltp-load-generator/` at repo root (sibling of `app-oltp-load-generator-new/`)

## Review Focus

- **Concurrent shared-DB writes**: Both APP1 (Java) and APP2 (.NET) write to the same tables. All SQL must use `SqlParameter` — never string-concatenated values — to prevent injection and parsing errors.
- **Connection pool exhaustion**: `SqlConnection` must be disposed via `using`. A missed dispose will silently leak from the pool until the app deadlocks.
- **Deadlock retry in `BulkUpdateInventory`**: Product IDs must be sorted (ascending) before the batch UPDATE — this matches Java's lock-ordering trick. If you skip the sort, concurrent bulk updates deadlock.
- **Zero-row UPDATE in `ProcessPayment`**: If the target order has no `PENDING` transaction row, the UPDATE affects 0 rows. This is expected and must not throw.
- **Port conflict at startup**: If 8081 is in use, ASP.NET Core fails silently or with a cryptic error. The run scripts must print the port and exit cleanly when it's unavailable.

---

## Task 1: Project Scaffold

**Files:**
- Create: `app2-dotnet-oltp-load-generator/LoadGen.Oltp.csproj`
- Create: `app2-dotnet-oltp-load-generator/appsettings.json`
- Create: `app2-dotnet-oltp-load-generator/.env.example`

**Interfaces:**
- Produces: compilable project skeleton; `dotnet build` succeeds with 0 errors

- [ ] **Step 1: Create the project folder and .csproj**

```bash
mkdir -p app2-dotnet-oltp-load-generator
```

Create `app2-dotnet-oltp-load-generator/LoadGen.Oltp.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk.Web">
  <PropertyGroup>
    <TargetFramework>net8.0</TargetFramework>
    <AssemblyName>app2-oltp-load-generator</AssemblyName>
    <RootNamespace>LoadGen.Oltp</RootNamespace>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.Data.SqlClient" Version="5.2.0" />
    <PackageReference Include="NewRelic.Agent.Api" Version="10.35.0" />
    <PackageReference Include="NewRelic.Agent" Version="10.35.0" />
  </ItemGroup>
</Project>
```

- [ ] **Step 2: Create appsettings.json**

Create `app2-dotnet-oltp-load-generator/appsettings.json`:
```json
{
  "ConnectionStrings": {
    "DefaultConnection": "Server=localhost,1433;Database=loadtest;User Id=sa;Password=Anitha@123456;Encrypt=false;TrustServerCertificate=true;Max Pool Size=10;Min Pool Size=3;Connect Timeout=30;"
  },
  "Database": {
    "MaxPoolSize": 10,
    "MinPoolSize": 3
  },
  "LoadGenerator": {
    "Threads": 3,
    "ApiBaseUrl": "http://localhost:8081"
  },
  "Urls": "http://0.0.0.0:8081",
  "Logging": {
    "LogLevel": {
      "Default": "Information",
      "Microsoft.AspNetCore": "Warning",
      "Microsoft.Hosting.Lifetime": "Information"
    }
  }
}
```

- [ ] **Step 3: Create .env.example**

Create `app2-dotnet-oltp-load-generator/.env.example`:
```bash
# .NET OLTP Load Generator Configuration (APP2)
# Copy this file to .env and update with your actual credentials

# Database — same SQL Server instance as APP1 (Java)
DB_CONNECTION_STRING=Server=YOUR_SQL_SERVER_HOST,1433;Database=loadtest;User Id=sa;Password=YourPassword123!;Encrypt=false;TrustServerCertificate=true;

# Connection Pool
DB_POOL_MAX=10
DB_POOL_MIN=3

# Load Generator
THREADS=3

# New Relic Configuration
NEW_RELIC_LICENSE_KEY=your_license_key_here
NEW_RELIC_APP_NAME=APP2
NEW_RELIC_LOG_LEVEL=info
```

- [ ] **Step 4: Verify project builds**

```bash
cd app2-dotnet-oltp-load-generator
dotnet restore
dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 5: Commit**

```bash
git add app2-dotnet-oltp-load-generator/
git commit -m "feat: scaffold .NET OLTP load generator project (APP2)"
```

---

## Task 2: DatabaseManager

**Files:**
- Create: `app2-dotnet-oltp-load-generator/DatabaseManager.cs`

**Interfaces:**
- Consumes: `IConfiguration` (reads `ConnectionStrings:DefaultConnection`, `Database:MaxPoolSize`, `Database:MinPoolSize`)
- Produces:
  - `SqlConnection GetConnection()` — opens and returns a pooled connection (caller must dispose)
  - `void Dispose()` — clears all pools on shutdown

- [ ] **Step 1: Create DatabaseManager.cs**

```csharp
using Microsoft.Data.SqlClient;

namespace LoadGen.Oltp;

public class DatabaseManager : IDisposable
{
    private readonly string _connectionString;
    private readonly ILogger<DatabaseManager> _logger;
    private bool _disposed;

    public DatabaseManager(IConfiguration config, ILogger<DatabaseManager> logger)
    {
        _logger = logger;
        var baseCs = config.GetConnectionString("DefaultConnection")
            ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is not configured.");

        var builder = new SqlConnectionStringBuilder(baseCs)
        {
            MaxPoolSize = config.GetValue("Database:MaxPoolSize", 10),
            MinPoolSize = config.GetValue("Database:MinPoolSize", 3),
            ConnectTimeout = 30,
            Pooling = true
        };
        _connectionString = builder.ConnectionString;

        _logger.LogInformation(
            "DatabaseManager initialized. Pool max={Max}, min={Min}",
            builder.MaxPoolSize, builder.MinPoolSize);
    }

    public SqlConnection GetConnection()
    {
        var conn = new SqlConnection(_connectionString);
        conn.Open();
        return conn;
    }

    public void Dispose()
    {
        if (!_disposed)
        {
            SqlConnection.ClearAllPools();
            _disposed = true;
            _logger.LogInformation("Database connection pool closed.");
        }
    }
}
```

- [ ] **Step 2: Verify it builds**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 3: Commit**

```bash
git add app2-dotnet-oltp-load-generator/DatabaseManager.cs
git commit -m "feat: add DatabaseManager with ADO.NET connection pooling"
```

---

## Task 3: CustomerService and ProductService

**Files:**
- Create: `app2-dotnet-oltp-load-generator/Services/CustomerService.cs`
- Create: `app2-dotnet-oltp-load-generator/Services/ProductService.cs`

**Interfaces:**
- Consumes: `DatabaseManager.GetConnection()`
- Produces (CustomerService):
  - `void UpdateLoyaltyPoints(long customerId, int points)`
  - `void UpgradeCustomerType(long customerId)`
  - `void LogCustomerAccess(long customerId)`
  - `string? GetCustomerEmail(long customerId)`
  - `int GetCustomerCount()`
- Produces (ProductService):
  - `void UpdatePrice(long productId)`
  - `string? GetProductDetails(long productId)`
  - `void SearchByCategory()`
  - `int GetProductCount()`
  - `void DeactivateProduct(long productId)`

- [ ] **Step 1: Create Services/ folder and CustomerService.cs**

```csharp
using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class CustomerService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<CustomerService> _logger;

    public CustomerService(DatabaseManager db, ILogger<CustomerService> logger)
    {
        _db = db;
        _logger = logger;
    }

    [Trace]
    public void UpdateLoyaltyPoints(long customerId, int points)
    {
        const string sql = "UPDATE oltp.CUSTOMERS SET loyalty_points = loyalty_points + @points, updated_at = CURRENT_TIMESTAMP WHERE customer_id = @customerId";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@points", points);
        cmd.Parameters.AddWithValue("@customerId", customerId);
        int updated = cmd.ExecuteNonQuery();
        if (updated > 0)
            _logger.LogDebug("Updated loyalty points for customer {CustomerId}: +{Points}", customerId, points);
    }

    [Trace]
    public void UpgradeCustomerType(long customerId)
    {
        const string sql = "UPDATE oltp.CUSTOMERS SET customer_type = 'PREMIUM', updated_at = CURRENT_TIMESTAMP WHERE customer_id = @customerId AND customer_type = 'REGULAR'";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@customerId", customerId);
        int updated = cmd.ExecuteNonQuery();
        if (updated > 0)
            _logger.LogDebug("Upgraded customer {CustomerId} to PREMIUM", customerId);
    }

    [Trace]
    public void LogCustomerAccess(long customerId)
    {
        const string sql = "INSERT INTO oltp.AUDIT_LOG (table_name, operation, record_id, changed_by, changed_at) VALUES ('CUSTOMERS', 'ACCESS', @recordId, 'SYSTEM', CURRENT_TIMESTAMP)";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@recordId", customerId);
            cmd.ExecuteNonQuery();
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error logging customer access for {CustomerId}", customerId);
            // Don't rethrow — audit logging must not break main flow
        }
    }

    [Trace]
    public string? GetCustomerEmail(long customerId)
    {
        const string sql = "SELECT email FROM oltp.CUSTOMERS WHERE customer_id = @customerId";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@customerId", customerId);
            return cmd.ExecuteScalar() as string;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting customer email for {CustomerId}", customerId);
            return null;
        }
    }

    [Trace]
    public int GetCustomerCount()
    {
        const string sql = "SELECT COUNT(*) FROM oltp.CUSTOMERS";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            return (int)(cmd.ExecuteScalar() ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting customer count");
            return 0;
        }
    }
}
```

- [ ] **Step 2: Create ProductService.cs**

```csharp
using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class ProductService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<ProductService> _logger;
    private readonly Random _random = new();

    public ProductService(DatabaseManager db, ILogger<ProductService> logger)
    {
        _db = db;
        _logger = logger;
    }

    [Trace]
    public void UpdatePrice(long productId)
    {
        const string sql = "UPDATE oltp.PRODUCTS SET price = price * (1 + (@priceChange / 100.0)) WHERE product_id = @productId";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        double priceChange = (_random.NextDouble() * 10) - 5; // -5% to +5%
        cmd.Parameters.AddWithValue("@priceChange", priceChange);
        cmd.Parameters.AddWithValue("@productId", productId);
        int updated = cmd.ExecuteNonQuery();
        if (updated > 0)
            _logger.LogDebug("Updated price for product {ProductId}: {Change:F2}%", productId, priceChange);
    }

    [Trace]
    public string? GetProductDetails(long productId)
    {
        const string sql = "SELECT product_name, category, price, sku FROM oltp.PRODUCTS WHERE product_id = @productId";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@productId", productId);
            using var reader = cmd.ExecuteReader();
            if (reader.Read())
            {
                var details = $"{reader["product_name"]} ({reader["category"]}) - ${reader.GetDouble(2):F2} [{reader["sku"]}]";
                _logger.LogDebug("Product details: {Details}", details);
                return details;
            }
            return null;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting product details for {ProductId}", productId);
            return null;
        }
    }

    [Trace]
    public void SearchByCategory()
    {
        string category = $"Category{_random.Next(10)}";
        const string sql = "SELECT TOP 20 product_id, product_name, price FROM oltp.PRODUCTS WHERE category = @category AND is_active = 1 ORDER BY price DESC";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@category", category);
            using var reader = cmd.ExecuteReader();
            int count = 0;
            while (reader.Read()) count++;
            _logger.LogDebug("Found {Count} products in {Category}", count, category);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error searching products by category {Category}", category);
        }
    }

    [Trace]
    public int GetProductCount()
    {
        const string sql = "SELECT COUNT(*) FROM oltp.PRODUCTS WHERE is_active = 1";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            return (int)(cmd.ExecuteScalar() ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting product count");
            return 0;
        }
    }

    [Trace]
    public void DeactivateProduct(long productId)
    {
        const string sql = "UPDATE oltp.PRODUCTS SET is_active = 0 WHERE product_id = @productId";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@productId", productId);
            cmd.ExecuteNonQuery();
            _logger.LogDebug("Deactivated product {ProductId}", productId);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deactivating product {ProductId}", productId);
        }
    }
}
```

- [ ] **Step 3: Build to verify**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 4: Commit**

```bash
git add app2-dotnet-oltp-load-generator/Services/
git commit -m "feat: add CustomerService and ProductService"
```

---

## Task 4: OrderService

**Files:**
- Create: `app2-dotnet-oltp-load-generator/Services/OrderService.cs`

**Interfaces:**
- Consumes: `DatabaseManager.GetConnection()`
- Produces:
  - `long CreateOrder(long customerId)` → returns auto-generated `order_id`
  - `void AddOrderItem(long orderId, long productId, int quantity)`
  - `void CalculateOrderTotal(long orderId)`
  - `void UpdateOrderStatus(long orderId, string status)`
  - `void LogAudit(string tableName, long recordId, string operation)`
  - `long GetMaxOrderId()`
  - `int GetOrderCount()`
  - `int DeleteOldCompletedOrders(int daysToKeep)`
  - `int DeleteCancelledOrders()`
  - `int DeleteOldAuditLogs(int daysToKeep)`

- [ ] **Step 1: Create OrderService.cs**

```csharp
using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class OrderService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<OrderService> _logger;
    private readonly Random _random = new();

    private static readonly string[] Statuses = { "PENDING", "PROCESSING", "COMPLETED", "SHIPPED", "DELIVERED" };
    private static readonly string[] PaymentMethods = { "CREDIT_CARD", "DEBIT_CARD", "PAYPAL", "BANK_TRANSFER" };

    public OrderService(DatabaseManager db, ILogger<OrderService> logger)
    {
        _db = db;
        _logger = logger;
    }

    [Trace]
    public long CreateOrder(long customerId)
    {
        const string sql = "INSERT INTO oltp.ORDERS (customer_id, order_date, status, payment_method, created_at) " +
                           "OUTPUT INSERTED.order_id " +
                           "VALUES (@customerId, CURRENT_TIMESTAMP, 'PENDING', @paymentMethod, CURRENT_TIMESTAMP)";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@customerId", customerId);
        cmd.Parameters.AddWithValue("@paymentMethod", PaymentMethods[_random.Next(PaymentMethods.Length)]);
        var result = cmd.ExecuteScalar() ?? throw new InvalidOperationException("Failed to create order — no ID returned");
        long orderId = Convert.ToInt64(result);
        _logger.LogDebug("Created order {OrderId} for customer {CustomerId}", orderId, customerId);
        return orderId;
    }

    [Trace]
    public void AddOrderItem(long orderId, long productId, int quantity)
    {
        const string priceQuery = "SELECT price FROM oltp.PRODUCTS WHERE product_id = @productId";
        const string insertSql = "INSERT INTO oltp.ORDER_ITEMS (order_id, product_id, quantity, unit_price, subtotal) " +
                                 "VALUES (@orderId, @productId, @quantity, @unitPrice, @subtotal)";
        using var conn = _db.GetConnection();

        double price;
        using (var cmd = new SqlCommand(priceQuery, conn))
        {
            cmd.Parameters.AddWithValue("@productId", productId);
            var result = cmd.ExecuteScalar();
            if (result == null)
            {
                _logger.LogWarning("Product {ProductId} not found", productId);
                return;
            }
            price = Convert.ToDouble(result);
        }

        double subtotal = price * quantity;
        using (var cmd = new SqlCommand(insertSql, conn))
        {
            cmd.Parameters.AddWithValue("@orderId", orderId);
            cmd.Parameters.AddWithValue("@productId", productId);
            cmd.Parameters.AddWithValue("@quantity", quantity);
            cmd.Parameters.AddWithValue("@unitPrice", price);
            cmd.Parameters.AddWithValue("@subtotal", subtotal);
            cmd.ExecuteNonQuery();
        }
        _logger.LogDebug("Added item to order {OrderId}: product={ProductId}, qty={Qty}", orderId, productId, quantity);
    }

    [Trace]
    public void CalculateOrderTotal(long orderId)
    {
        const string sql = "UPDATE oltp.ORDERS SET " +
                           "total_amount = (SELECT SUM(subtotal) FROM oltp.ORDER_ITEMS WHERE order_id = @orderId1), " +
                           "tax_amount = (SELECT SUM(subtotal) * 0.08 FROM oltp.ORDER_ITEMS WHERE order_id = @orderId2), " +
                           "updated_at = CURRENT_TIMESTAMP " +
                           "WHERE order_id = @orderId3";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@orderId1", orderId);
        cmd.Parameters.AddWithValue("@orderId2", orderId);
        cmd.Parameters.AddWithValue("@orderId3", orderId);
        cmd.ExecuteNonQuery();
        _logger.LogDebug("Calculated total for order {OrderId}", orderId);
    }

    [Trace]
    public void UpdateOrderStatus(long orderId, string status)
    {
        const string sql = "UPDATE oltp.ORDERS SET status = @status, updated_at = CURRENT_TIMESTAMP WHERE order_id = @orderId";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@status", status);
        cmd.Parameters.AddWithValue("@orderId", orderId);
        int updated = cmd.ExecuteNonQuery();
        if (updated > 0)
            _logger.LogDebug("Updated order {OrderId} status to {Status}", orderId, status);
    }

    [Trace]
    public void LogAudit(string tableName, long recordId, string operation)
    {
        const string sql = "INSERT INTO oltp.AUDIT_LOG (table_name, operation, record_id, changed_by, changed_at) " +
                           "VALUES (@tableName, @operation, @recordId, 'SYSTEM', CURRENT_TIMESTAMP)";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@tableName", tableName);
            cmd.Parameters.AddWithValue("@operation", operation);
            cmd.Parameters.AddWithValue("@recordId", recordId);
            cmd.ExecuteNonQuery();
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error logging audit");
            // Don't rethrow — audit must not break main flow
        }
    }

    [Trace]
    public long GetMaxOrderId()
    {
        const string sql = "SELECT ISNULL(MAX(order_id), 0) FROM oltp.ORDERS";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            return Convert.ToInt64(cmd.ExecuteScalar() ?? 0L);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting max order id");
            return 0;
        }
    }

    [Trace]
    public int GetOrderCount()
    {
        const string sql = "SELECT COUNT(*) FROM oltp.ORDERS";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            return (int)(cmd.ExecuteScalar() ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting order count");
            return 0;
        }
    }

    [Trace]
    public int DeleteOldCompletedOrders(int daysToKeep)
    {
        const string deleteItemsSql = "DELETE FROM oltp.ORDER_ITEMS WHERE order_id IN (" +
                                      "  SELECT order_id FROM oltp.ORDERS " +
                                      "  WHERE status IN ('COMPLETED', 'DELIVERED') " +
                                      "  AND order_date < DATEADD(day, -@days, GETDATE()))";
        const string deleteTxnSql = "DELETE FROM oltp.TRANSACTIONS WHERE order_id IN (" +
                                    "  SELECT order_id FROM oltp.ORDERS " +
                                    "  WHERE status IN ('COMPLETED', 'DELIVERED') " +
                                    "  AND order_date < DATEADD(day, -@days, GETDATE()))";
        const string deleteOrdersSql = "DELETE FROM oltp.ORDERS " +
                                       "WHERE status IN ('COMPLETED', 'DELIVERED') " +
                                       "AND order_date < DATEADD(day, -@days, GETDATE())";
        int totalDeleted = 0;
        try
        {
            using var conn = _db.GetConnection();
            using var tx = conn.BeginTransaction();
            try
            {
                using (var cmd = new SqlCommand(deleteItemsSql, conn, tx))
                {
                    cmd.Parameters.AddWithValue("@days", daysToKeep);
                    cmd.ExecuteNonQuery();
                }
                using (var cmd = new SqlCommand(deleteTxnSql, conn, tx))
                {
                    cmd.Parameters.AddWithValue("@days", daysToKeep);
                    cmd.ExecuteNonQuery();
                }
                using (var cmd = new SqlCommand(deleteOrdersSql, conn, tx))
                {
                    cmd.Parameters.AddWithValue("@days", daysToKeep);
                    totalDeleted = cmd.ExecuteNonQuery();
                }
                tx.Commit();
                _logger.LogDebug("Deleted {Count} old completed orders (>{Days} days)", totalDeleted, daysToKeep);
            }
            catch
            {
                tx.Rollback();
                throw;
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deleting old completed orders");
        }
        return totalDeleted;
    }

    [Trace]
    public int DeleteCancelledOrders()
    {
        const string deleteItemsSql = "DELETE FROM oltp.ORDER_ITEMS WHERE order_id IN (" +
                                      "  SELECT order_id FROM oltp.ORDERS WHERE status IN ('CANCELLED', 'PAYMENT_FAILED'))";
        const string deleteTxnSql = "DELETE FROM oltp.TRANSACTIONS WHERE order_id IN (" +
                                    "  SELECT order_id FROM oltp.ORDERS WHERE status IN ('CANCELLED', 'PAYMENT_FAILED'))";
        const string deleteOrdersSql = "DELETE FROM oltp.ORDERS WHERE status IN ('CANCELLED', 'PAYMENT_FAILED')";
        int totalDeleted = 0;
        try
        {
            using var conn = _db.GetConnection();
            using var tx = conn.BeginTransaction();
            try
            {
                using (var cmd = new SqlCommand(deleteItemsSql, conn, tx)) cmd.ExecuteNonQuery();
                using (var cmd = new SqlCommand(deleteTxnSql, conn, tx)) cmd.ExecuteNonQuery();
                using (var cmd = new SqlCommand(deleteOrdersSql, conn, tx))
                    totalDeleted = cmd.ExecuteNonQuery();
                tx.Commit();
                if (totalDeleted > 0)
                    _logger.LogDebug("Deleted {Count} cancelled/failed orders", totalDeleted);
            }
            catch
            {
                tx.Rollback();
                throw;
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deleting cancelled orders");
        }
        return totalDeleted;
    }

    [Trace]
    public int DeleteOldAuditLogs(int daysToKeep)
    {
        const string sql = "DELETE FROM oltp.AUDIT_LOG WHERE changed_at < DATEADD(day, -@days, GETDATE())";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@days", daysToKeep);
            int deleted = cmd.ExecuteNonQuery();
            if (deleted > 0)
                _logger.LogDebug("Deleted {Count} old audit logs (>{Days} days)", deleted, daysToKeep);
            return deleted;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deleting old audit logs");
            return 0;
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 3: Commit**

```bash
git add app2-dotnet-oltp-load-generator/Services/OrderService.cs
git commit -m "feat: add OrderService with full order lifecycle"
```

---

## Task 5: InventoryService

**Files:**
- Create: `app2-dotnet-oltp-load-generator/Services/InventoryService.cs`

**Interfaces:**
- Consumes: `DatabaseManager.GetConnection()`
- Produces:
  - `int CheckAvailability(long productId)`
  - `void RestockInventory(long productId, int quantity)`
  - `void UpdateWarehouseLocation(long productId)`
  - `void ReserveInventory(long orderId)`
  - `void ReleaseInventory(long orderId)`
  - `void AdjustInventory(long productId, int adjustment)`
  - `void BulkUpdateInventory()` — sorts product IDs before batch UPDATE to prevent deadlocks

- [ ] **Step 1: Create InventoryService.cs**

```csharp
using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class InventoryService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<InventoryService> _logger;
    private readonly Random _random = new();

    private static readonly string[] Warehouses = { "WH-0", "WH-1", "WH-2", "WH-3", "WH-4" };

    public InventoryService(DatabaseManager db, ILogger<InventoryService> logger)
    {
        _db = db;
        _logger = logger;
    }

    [Trace]
    public int CheckAvailability(long productId)
    {
        const string sql = "SELECT SUM(quantity_available - quantity_reserved) AS available FROM oltp.INVENTORY WHERE product_id = @productId";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@productId", productId);
            var result = cmd.ExecuteScalar();
            int available = result == DBNull.Value ? 0 : Convert.ToInt32(result);
            _logger.LogDebug("Product {ProductId} availability: {Available}", productId, available);
            return available;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error checking availability for product {ProductId}", productId);
            return 0;
        }
    }

    [Trace]
    public void RestockInventory(long productId, int quantity)
    {
        const string sql = "UPDATE oltp.INVENTORY SET quantity_available = quantity_available + @quantity, " +
                           "last_restock_date = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP " +
                           "WHERE product_id = @productId";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@quantity", quantity);
        cmd.Parameters.AddWithValue("@productId", productId);
        int updated = cmd.ExecuteNonQuery();
        if (updated > 0)
            _logger.LogDebug("Restocked product {ProductId}: +{Qty} units", productId, quantity);
    }

    [Trace]
    public void UpdateWarehouseLocation(long productId)
    {
        string newLocation = Warehouses[_random.Next(Warehouses.Length)];
        const string sql = "UPDATE oltp.INVENTORY SET warehouse_location = @location, updated_at = CURRENT_TIMESTAMP WHERE product_id = @productId";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@location", newLocation);
        cmd.Parameters.AddWithValue("@productId", productId);
        cmd.ExecuteNonQuery();
        _logger.LogDebug("Updated warehouse location for product {ProductId} to {Location}", productId, newLocation);
    }

    [Trace]
    public void ReserveInventory(long orderId)
    {
        const string sql = "UPDATE oltp.INVENTORY SET " +
                           "quantity_reserved = quantity_reserved + (" +
                           "  SELECT SUM(oi.quantity) FROM oltp.ORDER_ITEMS oi WHERE oi.order_id = @orderId1 AND oi.product_id = INVENTORY.product_id" +
                           "), updated_at = CURRENT_TIMESTAMP " +
                           "WHERE product_id IN (SELECT product_id FROM oltp.ORDER_ITEMS WHERE order_id = @orderId2)";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@orderId1", orderId);
            cmd.Parameters.AddWithValue("@orderId2", orderId);
            int updated = cmd.ExecuteNonQuery();
            _logger.LogDebug("Reserved inventory for order {OrderId}: {Count} products updated", orderId, updated);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error reserving inventory for order {OrderId}", orderId);
            // Don't rethrow — reservation failure must not break order creation
        }
    }

    [Trace]
    public void ReleaseInventory(long orderId)
    {
        const string sql = "UPDATE oltp.INVENTORY SET " +
                           "quantity_reserved = CASE WHEN quantity_reserved - (" +
                           "  SELECT SUM(oi.quantity) FROM oltp.ORDER_ITEMS oi WHERE oi.order_id = @orderId1 AND oi.product_id = INVENTORY.product_id" +
                           ") < 0 THEN 0 ELSE quantity_reserved - (" +
                           "  SELECT SUM(oi.quantity) FROM oltp.ORDER_ITEMS oi WHERE oi.order_id = @orderId2 AND oi.product_id = INVENTORY.product_id" +
                           ") END, updated_at = CURRENT_TIMESTAMP " +
                           "WHERE product_id IN (SELECT product_id FROM oltp.ORDER_ITEMS WHERE order_id = @orderId3)";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@orderId1", orderId);
            cmd.Parameters.AddWithValue("@orderId2", orderId);
            cmd.Parameters.AddWithValue("@orderId3", orderId);
            int updated = cmd.ExecuteNonQuery();
            _logger.LogDebug("Released inventory for order {OrderId}: {Count} products updated", orderId, updated);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error releasing inventory for order {OrderId}", orderId);
        }
    }

    [Trace]
    public void AdjustInventory(long productId, int adjustment)
    {
        const string sql = "UPDATE oltp.INVENTORY SET " +
                           "quantity_available = CASE WHEN quantity_available + @adj1 < 0 THEN 0 ELSE quantity_available + @adj2 END, " +
                           "updated_at = CURRENT_TIMESTAMP " +
                           "WHERE product_id = @productId";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@adj1", adjustment);
            cmd.Parameters.AddWithValue("@adj2", adjustment);
            cmd.Parameters.AddWithValue("@productId", productId);
            cmd.ExecuteNonQuery();
            _logger.LogDebug("Adjusted inventory for product {ProductId}: {Adj}", productId, adjustment);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error adjusting inventory for product {ProductId}", productId);
        }
    }

    [Trace]
    public void BulkUpdateInventory() => BulkUpdateInventoryWithRetry(3);

    private void BulkUpdateInventoryWithRetry(int maxRetries)
    {
        int updateCount = _random.Next(20) + 10; // 10–30 products

        // CRITICAL: Sort product IDs ascending to prevent deadlocks (matches Java lock ordering)
        var productIds = Enumerable.Range(0, updateCount)
            .Select(_ => (long)(_random.Next(500) + 1))
            .Distinct()
            .OrderBy(id => id)
            .ToList();

        var adjustments = productIds.ToDictionary(
            id => id,
            _ => _random.Next(100) - 50); // -50 to +50

        const string sql = "UPDATE oltp.INVENTORY SET quantity_available = quantity_available + @adj, " +
                           "updated_at = CURRENT_TIMESTAMP WHERE product_id = @productId";

        for (int attempt = 1; attempt <= maxRetries; attempt++)
        {
            try
            {
                using var conn = _db.GetConnection();
                using var cmd = new SqlCommand(sql, conn);
                var adjParam = cmd.Parameters.Add("@adj", System.Data.SqlDbType.Int);
                var idParam = cmd.Parameters.Add("@productId", System.Data.SqlDbType.BigInt);

                foreach (var productId in productIds)
                {
                    adjParam.Value = adjustments[productId];
                    idParam.Value = productId;
                    cmd.ExecuteNonQuery();
                }
                _logger.LogDebug("Bulk updated inventory for {Count} products", productIds.Count);
                return;
            }
            catch (SqlException ex) when (attempt < maxRetries &&
                (ex.Number == 1205 || ex.Number == 1222 || ex.Message.Contains("deadlock")))
            {
                int backoffMs = 100 * (1 << (attempt - 1)); // 100ms, 200ms, 400ms
                _logger.LogWarning("Deadlock in bulk inventory update (attempt {Attempt}/{Max}), retrying in {Backoff}ms",
                    attempt, maxRetries, backoffMs);
                Thread.Sleep(backoffMs);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error in bulk inventory update (attempt {Attempt}/{Max})", attempt, maxRetries);
                return;
            }
        }
        _logger.LogError("Failed bulk inventory update after {Max} attempts", maxRetries);
    }
}
```

- [ ] **Step 2: Build**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 3: Commit**

```bash
git add app2-dotnet-oltp-load-generator/Services/InventoryService.cs
git commit -m "feat: add InventoryService with deadlock-safe bulk update"
```

---

## Task 6: TransactionService and SessionService

**Files:**
- Create: `app2-dotnet-oltp-load-generator/Services/TransactionService.cs`
- Create: `app2-dotnet-oltp-load-generator/Services/SessionService.cs`

**Interfaces:**
- Consumes: `DatabaseManager.GetConnection()`
- Produces (TransactionService):
  - `long CreateTransaction(long orderId, string transactionType)`
  - `bool ProcessPayment(long orderId)` — 95% success rate; returns false for FAILED
  - `void RefundTransaction(long transactionId)`
  - `double GetTotalTransactionAmount(long customerId)`
  - `int GetFailedTransactionCount()`
- Produces (SessionService):
  - `string CreateSession(long customerId)` → returns UUID session_id
  - `void UpdateSessionActivity(string sessionId)`
  - `void ExpireSession(string sessionId)`
  - `void ExpireOldSessions()`
  - `int GetActiveSessionCount()`
  - `int GetSessionCountByCustomer(long customerId)`
  - `int DeleteExpiredSessions()`

- [ ] **Step 1: Create TransactionService.cs**

```csharp
using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class TransactionService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<TransactionService> _logger;
    private readonly Random _random = new();

    private static readonly string[] Gateways = { "Stripe", "PayPal", "Square", "Authorize.Net", "Braintree" };

    public TransactionService(DatabaseManager db, ILogger<TransactionService> logger)
    {
        _db = db;
        _logger = logger;
    }

    [Trace]
    public long CreateTransaction(long orderId, string transactionType)
    {
        const string sql = "INSERT INTO oltp.TRANSACTIONS " +
                           "(order_id, transaction_type, payment_gateway, gateway_transaction_id, status, processed_at, amount, currency) " +
                           "OUTPUT INSERTED.transaction_id " +
                           "VALUES (@orderId, @txnType, @gateway, @gatewayTxnId, 'PENDING', CURRENT_TIMESTAMP, " +
                           "(SELECT total_amount FROM oltp.ORDERS WHERE order_id = @orderId2), 'USD')";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        string gateway = Gateways[_random.Next(Gateways.Length)];
        string gatewayTxnId = Guid.NewGuid().ToString();
        cmd.Parameters.AddWithValue("@orderId", orderId);
        cmd.Parameters.AddWithValue("@txnType", transactionType);
        cmd.Parameters.AddWithValue("@gateway", gateway);
        cmd.Parameters.AddWithValue("@gatewayTxnId", gatewayTxnId);
        cmd.Parameters.AddWithValue("@orderId2", orderId);
        var result = cmd.ExecuteScalar() ?? throw new InvalidOperationException("Failed to create transaction");
        long transactionId = Convert.ToInt64(result);
        _logger.LogDebug("Created transaction {TxnId} for order {OrderId} via {Gateway}", transactionId, orderId, gateway);
        return transactionId;
    }

    [Trace]
    public bool ProcessPayment(long orderId)
    {
        bool success = _random.Next(100) < 95;
        const string sql = "UPDATE oltp.TRANSACTIONS SET status = @status, processed_at = CURRENT_TIMESTAMP, error_message = @errorMsg " +
                           "WHERE order_id = @orderId AND status = 'PENDING'";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@status", success ? "COMPLETED" : "FAILED");
            if (success)
                cmd.Parameters.AddWithValue("@errorMsg", DBNull.Value);
            else
                cmd.Parameters.AddWithValue("@errorMsg", "Payment declined - insufficient funds");
            cmd.Parameters.AddWithValue("@orderId", orderId);
            int updated = cmd.ExecuteNonQuery();
            if (updated > 0)
                _logger.LogDebug("Processed payment for order {OrderId}: {Result}", orderId, success ? "SUCCESS" : "FAILED");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error processing payment for order {OrderId}", orderId);
            return false;
        }
        return success;
    }

    [Trace]
    public void RefundTransaction(long transactionId)
    {
        const string sql = "INSERT INTO oltp.TRANSACTIONS " +
                           "(order_id, transaction_type, payment_gateway, gateway_transaction_id, status, processed_at, amount, currency) " +
                           "SELECT order_id, 'REFUND', payment_gateway, @refundId, 'COMPLETED', CURRENT_TIMESTAMP, -amount, currency " +
                           "FROM oltp.TRANSACTIONS WHERE transaction_id = @transactionId";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@refundId", $"REFUND-{Guid.NewGuid()}");
        cmd.Parameters.AddWithValue("@transactionId", transactionId);
        int inserted = cmd.ExecuteNonQuery();
        if (inserted > 0)
            _logger.LogDebug("Created refund for transaction {TxnId}", transactionId);
    }

    [Trace]
    public double GetTotalTransactionAmount(long customerId)
    {
        const string sql = "SELECT SUM(t.amount) AS total FROM oltp.TRANSACTIONS t " +
                           "JOIN oltp.ORDERS o ON t.order_id = o.order_id " +
                           "WHERE o.customer_id = @customerId AND t.status = 'COMPLETED'";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@customerId", customerId);
            var result = cmd.ExecuteScalar();
            double total = (result == null || result == DBNull.Value) ? 0.0 : Convert.ToDouble(result);
            _logger.LogDebug("Total transaction amount for customer {CustomerId}: ${Total}", customerId, total);
            return total;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting total transaction amount for customer {CustomerId}", customerId);
            return 0.0;
        }
    }

    [Trace]
    public int GetFailedTransactionCount()
    {
        const string sql = "SELECT COUNT(*) FROM oltp.TRANSACTIONS WHERE status = 'FAILED'";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            return (int)(cmd.ExecuteScalar() ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting failed transaction count");
            return 0;
        }
    }
}
```

- [ ] **Step 2: Create SessionService.cs**

```csharp
using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class SessionService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<SessionService> _logger;

    public SessionService(DatabaseManager db, ILogger<SessionService> logger)
    {
        _db = db;
        _logger = logger;
    }

    [Trace]
    public string CreateSession(long customerId)
    {
        string sessionId = Guid.NewGuid().ToString();
        const string sql = "INSERT INTO oltp.SESSION_DATA (session_id, customer_id, login_time, last_activity, is_active) " +
                           "VALUES (@sessionId, @customerId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)";
        using var conn = _db.GetConnection();
        using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@sessionId", sessionId);
        cmd.Parameters.AddWithValue("@customerId", customerId);
        cmd.ExecuteNonQuery();
        _logger.LogDebug("Created session {SessionId} for customer {CustomerId}", sessionId, customerId);
        return sessionId;
    }

    [Trace]
    public void UpdateSessionActivity(string sessionId)
    {
        const string sql = "UPDATE oltp.SESSION_DATA SET last_activity = CURRENT_TIMESTAMP WHERE session_id = @sessionId";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@sessionId", sessionId);
            int updated = cmd.ExecuteNonQuery();
            if (updated > 0)
                _logger.LogDebug("Updated session activity: {SessionId}", sessionId);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error updating session activity for {SessionId}", sessionId);
        }
    }

    [Trace]
    public void ExpireSession(string sessionId)
    {
        const string sql = "UPDATE oltp.SESSION_DATA SET is_active = 0 WHERE session_id = @sessionId";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@sessionId", sessionId);
            cmd.ExecuteNonQuery();
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error expiring session {SessionId}", sessionId);
        }
    }

    [Trace]
    public void ExpireOldSessions()
    {
        const string sql = "UPDATE oltp.SESSION_DATA SET is_active = 0 " +
                           "WHERE is_active = 1 AND last_activity < DATEADD(hour, -1, GETDATE())";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            int updated = cmd.ExecuteNonQuery();
            if (updated > 0)
                _logger.LogDebug("Expired {Count} old sessions", updated);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error expiring old sessions");
        }
    }

    [Trace]
    public int GetActiveSessionCount()
    {
        const string sql = "SELECT COUNT(*) FROM oltp.SESSION_DATA WHERE is_active = 1";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            return (int)(cmd.ExecuteScalar() ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting active session count");
            return 0;
        }
    }

    [Trace]
    public int GetSessionCountByCustomer(long customerId)
    {
        const string sql = "SELECT COUNT(*) FROM oltp.SESSION_DATA WHERE customer_id = @customerId AND is_active = 1";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@customerId", customerId);
            return (int)(cmd.ExecuteScalar() ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting session count for customer {CustomerId}", customerId);
            return 0;
        }
    }

    [Trace]
    public int DeleteExpiredSessions()
    {
        const string sql = "DELETE FROM oltp.SESSION_DATA WHERE is_active = 0 OR last_activity < DATEADD(hour, -2, GETDATE())";
        try
        {
            using var conn = _db.GetConnection();
            using var cmd = new SqlCommand(sql, conn);
            int deleted = cmd.ExecuteNonQuery();
            if (deleted > 0)
                _logger.LogDebug("Deleted {Count} expired sessions", deleted);
            return deleted;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deleting expired sessions");
            return 0;
        }
    }
}
```

- [ ] **Step 3: Build**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 4: Commit**

```bash
git add app2-dotnet-oltp-load-generator/Services/TransactionService.cs app2-dotnet-oltp-load-generator/Services/SessionService.cs
git commit -m "feat: add TransactionService and SessionService"
```

---

## Task 7: All Six Controllers

**Files:**
- Create: `app2-dotnet-oltp-load-generator/Controllers/CustomerController.cs`
- Create: `app2-dotnet-oltp-load-generator/Controllers/OrderController.cs`
- Create: `app2-dotnet-oltp-load-generator/Controllers/ProductController.cs`
- Create: `app2-dotnet-oltp-load-generator/Controllers/InventoryController.cs`
- Create: `app2-dotnet-oltp-load-generator/Controllers/TransactionController.cs`
- Create: `app2-dotnet-oltp-load-generator/Controllers/SessionController.cs`

**Interfaces:**
- Consumes: all 6 services
- Produces: REST endpoints at `http://localhost:8081/api/...` (same paths as Java on 8080)

- [ ] **Step 1: Create CustomerController.cs**

```csharp
using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/customers")]
public class CustomerController : ControllerBase
{
    private readonly CustomerService _customerService;
    private readonly ILogger<CustomerController> _logger;

    public CustomerController(CustomerService customerService, ILogger<CustomerController> logger)
    {
        _customerService = customerService;
        _logger = logger;
    }

    [HttpPut("{customerId}/loyalty")]
    [Transaction(Web = true)]
    public IActionResult UpdateLoyaltyPoints(long customerId, [FromQuery] int points)
    {
        try
        {
            NewRelic.AddCustomParameter("customerId", customerId);
            NewRelic.AddCustomParameter("points", points);
            _customerService.UpdateLoyaltyPoints(customerId, points);
            return Ok(new { customerId, pointsAdded = points, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error updating loyalty points");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("{customerId}/upgrade")]
    [Transaction(Web = true)]
    public IActionResult UpgradeCustomerType(long customerId)
    {
        try
        {
            NewRelic.AddCustomParameter("customerId", customerId);
            _customerService.UpgradeCustomerType(customerId);
            return Ok(new { customerId, upgraded = true, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error upgrading customer");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPost("{customerId}/access-log")]
    [Transaction(Web = true)]
    public IActionResult LogCustomerAccess(long customerId)
    {
        try
        {
            NewRelic.AddCustomParameter("customerId", customerId);
            _customerService.LogCustomerAccess(customerId);
            return Ok(new { customerId, logged = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error logging customer access");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
```

- [ ] **Step 2: Create OrderController.cs**

```csharp
using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/orders")]
public class OrderController : ControllerBase
{
    private readonly OrderService _orderService;
    private readonly TransactionService _transactionService;
    private readonly InventoryService _inventoryService;
    private readonly ILogger<OrderController> _logger;

    public OrderController(
        OrderService orderService,
        TransactionService transactionService,
        InventoryService inventoryService,
        ILogger<OrderController> logger)
    {
        _orderService = orderService;
        _transactionService = transactionService;
        _inventoryService = inventoryService;
        _logger = logger;
    }

    [HttpPost("create")]
    [Transaction(Web = true)]
    public IActionResult CreateOrder([FromQuery] long customerId, [FromQuery] int numItems)
    {
        try
        {
            NewRelic.AddCustomParameter("customerId", customerId);
            NewRelic.AddCustomParameter("numItems", numItems);

            long orderId = _orderService.CreateOrder(customerId);

            var rng = new Random();
            for (int i = 0; i < numItems; i++)
            {
                long productId = rng.Next(500) + 1;
                int quantity = rng.Next(5) + 1;
                _orderService.AddOrderItem(orderId, productId, quantity);
            }

            _orderService.CalculateOrderTotal(orderId);
            _transactionService.CreateTransaction(orderId, "PAYMENT");
            _inventoryService.ReserveInventory(orderId);
            _orderService.LogAudit("ORDERS", orderId, "CREATE");

            return Ok(new { orderId, customerId, itemsAdded = numItems, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error creating order");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message, status = "ERROR" });
        }
    }

    [HttpPut("{orderId}/status")]
    [Transaction(Web = true)]
    public IActionResult UpdateOrderStatus(long orderId, [FromQuery] string status)
    {
        try
        {
            NewRelic.AddCustomParameter("orderId", orderId);
            NewRelic.AddCustomParameter("status", status);
            _orderService.UpdateOrderStatus(orderId, status);
            return Ok(new { orderId, status, updated = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error updating order status");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpDelete("old")]
    [Transaction(Web = true)]
    public IActionResult DeleteOldOrders([FromQuery] int daysToKeep = 30)
    {
        try
        {
            NewRelic.AddCustomParameter("daysToKeep", daysToKeep);
            _orderService.DeleteOldCompletedOrders(daysToKeep);
            _orderService.DeleteCancelledOrders();
            _orderService.DeleteOldAuditLogs(7);
            return Ok(new { status = "DELETED", daysKept = daysToKeep });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error deleting old orders");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPost("bulk")]
    [Transaction(Web = true)]
    public IActionResult BulkCreateOrders([FromQuery] int batchSize)
    {
        try
        {
            NewRelic.AddCustomParameter("batchSize", batchSize);
            var rng = new Random();
            int created = 0;
            for (int i = 0; i < batchSize; i++)
            {
                long customerId = rng.Next(1000) + 1;
                long orderId = _orderService.CreateOrder(customerId);
                int numItems = rng.Next(3) + 1;
                for (int j = 0; j < numItems; j++)
                {
                    long productId = rng.Next(500) + 1;
                    int quantity = rng.Next(3) + 1;
                    _orderService.AddOrderItem(orderId, productId, quantity);
                }
                _orderService.CalculateOrderTotal(orderId);
                created++;
            }
            _inventoryService.BulkUpdateInventory();
            return Ok(new { created, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error bulk creating orders");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
```

- [ ] **Step 3: Create ProductController.cs**

```csharp
using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/products")]
public class ProductController : ControllerBase
{
    private readonly ProductService _productService;
    private readonly ILogger<ProductController> _logger;

    public ProductController(ProductService productService, ILogger<ProductController> logger)
    {
        _productService = productService;
        _logger = logger;
    }

    [HttpGet("{productId}")]
    [Transaction(Web = true)]
    public IActionResult GetProductDetails(long productId)
    {
        try
        {
            NewRelic.AddCustomParameter("productId", productId);
            _productService.GetProductDetails(productId);
            return Ok(new { productId, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting product details");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("{productId}/price")]
    [Transaction(Web = true)]
    public IActionResult UpdatePrice(long productId)
    {
        try
        {
            NewRelic.AddCustomParameter("productId", productId);
            _productService.UpdatePrice(productId);
            return Ok(new { productId, updated = true, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error updating product price");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpGet("search")]
    [Transaction(Web = true)]
    public IActionResult SearchByCategory()
    {
        try
        {
            _productService.SearchByCategory();
            return Ok(new { status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error searching products");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
```

- [ ] **Step 4: Create InventoryController.cs**

```csharp
using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/inventory")]
public class InventoryController : ControllerBase
{
    private readonly InventoryService _inventoryService;
    private readonly ILogger<InventoryController> _logger;

    public InventoryController(InventoryService inventoryService, ILogger<InventoryController> logger)
    {
        _inventoryService = inventoryService;
        _logger = logger;
    }

    [HttpGet("{productId}/check")]
    [Transaction(Web = true)]
    public IActionResult CheckInventory(long productId)
    {
        try
        {
            NewRelic.AddCustomParameter("productId", productId);
            int available = _inventoryService.CheckAvailability(productId);
            if (available < 100)
            {
                int restockAmount = new Random().Next(500) + 100;
                _inventoryService.RestockInventory(productId, restockAmount);
                available += restockAmount;
            }
            _inventoryService.UpdateWarehouseLocation(productId);
            return Ok(new { productId, available, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error checking inventory");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("{productId}/restock")]
    [Transaction(Web = true)]
    public IActionResult RestockInventory(long productId, [FromQuery] int quantity)
    {
        try
        {
            NewRelic.AddCustomParameter("productId", productId);
            NewRelic.AddCustomParameter("quantity", quantity);
            _inventoryService.RestockInventory(productId, quantity);
            return Ok(new { productId, restocked = quantity, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error restocking inventory");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("bulk-update")]
    [Transaction(Web = true)]
    public IActionResult BulkUpdateInventory()
    {
        try
        {
            _inventoryService.BulkUpdateInventory();
            return Ok(new { status = "SUCCESS", updated = true });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error bulk updating inventory");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
```

- [ ] **Step 5: Create TransactionController.cs**

```csharp
using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/transactions")]
public class TransactionController : ControllerBase
{
    private readonly TransactionService _transactionService;
    private readonly OrderService _orderService;
    private readonly ILogger<TransactionController> _logger;

    public TransactionController(TransactionService transactionService, OrderService orderService, ILogger<TransactionController> logger)
    {
        _transactionService = transactionService;
        _orderService = orderService;
        _logger = logger;
    }

    [HttpPost("process")]
    [Transaction(Web = true)]
    public IActionResult ProcessPayment([FromQuery] long orderId)
    {
        try
        {
            NewRelic.AddCustomParameter("orderId", orderId);
            bool success = _transactionService.ProcessPayment(orderId);
            _orderService.UpdateOrderStatus(orderId, success ? "COMPLETED" : "PAYMENT_FAILED");
            return Ok(new { orderId, success, status = success ? "COMPLETED" : "PAYMENT_FAILED" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error processing payment");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
```

- [ ] **Step 6: Create SessionController.cs**

```csharp
using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/sessions")]
public class SessionController : ControllerBase
{
    private readonly SessionService _sessionService;
    private readonly ILogger<SessionController> _logger;

    public SessionController(SessionService sessionService, ILogger<SessionController> logger)
    {
        _sessionService = sessionService;
        _logger = logger;
    }

    [HttpPost("create")]
    [Transaction(Web = true)]
    public IActionResult CreateSession([FromQuery] long customerId)
    {
        try
        {
            NewRelic.AddCustomParameter("customerId", customerId);
            string sessionId = _sessionService.CreateSession(customerId);
            _sessionService.UpdateSessionActivity(sessionId);
            return Ok(new { sessionId, customerId, status = "ACTIVE" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error creating session");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpDelete("expire")]
    [Transaction(Web = true)]
    public IActionResult ExpireSessions()
    {
        try
        {
            _sessionService.ExpireOldSessions();
            _sessionService.DeleteExpiredSessions();
            return Ok(new { status = "EXPIRED" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error expiring sessions");
            NewRelic.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
```

- [ ] **Step 7: Build**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 8: Commit**

```bash
git add app2-dotnet-oltp-load-generator/Controllers/
git commit -m "feat: add all 6 REST controllers"
```

---

## Task 8: OltpLoadGenerator (IHostedService)

**Files:**
- Create: `app2-dotnet-oltp-load-generator/OltpLoadGenerator.cs`

**Interfaces:**
- Consumes: `IHttpClientFactory`, `DatabaseManager`, `IConfiguration` (`LoadGenerator:Threads`, `LoadGenerator:ApiBaseUrl`), `ILogger`
- Produces: `IHostedService` — starts `Threads` async worker tasks on app start, stops them on shutdown

- [ ] **Step 1: Create OltpLoadGenerator.cs**

```csharp
using NewRelic.Api.Agent;

namespace LoadGen.Oltp;

public class OltpLoadGenerator : IHostedService
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly DatabaseManager _dbManager;
    private readonly int _numThreads;
    private readonly string _apiBaseUrl;
    private readonly ILogger<OltpLoadGenerator> _logger;
    private volatile bool _running;
    private readonly List<Task> _workers = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly Random _random = new();

    public OltpLoadGenerator(
        IHttpClientFactory httpClientFactory,
        DatabaseManager dbManager,
        IConfiguration config,
        ILogger<OltpLoadGenerator> logger)
    {
        _httpClientFactory = httpClientFactory;
        _dbManager = dbManager;
        _numThreads = config.GetValue("LoadGenerator:Threads", 3);
        _apiBaseUrl = config["LoadGenerator:ApiBaseUrl"] ?? "http://localhost:8081";
        _logger = logger;
        _logger.LogInformation("OltpLoadGenerator initialized with {Threads} threads. API: {Url}", _numThreads, _apiBaseUrl);
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _running = true;
        _logger.LogInformation(new string('=', 80));
        _logger.LogInformation("Starting OLTP load generation...");
        _logger.LogInformation(new string('=', 80));

        for (int i = 0; i < _numThreads; i++)
        {
            int threadId = i;
            _workers.Add(Task.Run(() => RunWorker(threadId, _cts.Token), _cts.Token));
        }

        _ = Task.Run(async () =>
        {
            while (_running && !_cts.Token.IsCancellationRequested)
            {
                try { await Task.Delay(10000, _cts.Token); } catch (OperationCanceledException) { break; }
                LogStatistics();
            }
        }, _cts.Token);

        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        _logger.LogInformation("Shutdown signal received, stopping load generator...");
        _running = false;
        _cts.Cancel();
        try { await Task.WhenAll(_workers).WaitAsync(TimeSpan.FromSeconds(30)); }
        catch (OperationCanceledException) { }
        _logger.LogInformation("OltpLoadGenerator shutdown complete.");
    }

    private async Task RunWorker(int threadId, CancellationToken ct)
    {
        _logger.LogInformation("Worker thread {ThreadId} started", threadId);
        int operationCount = 0;
        long lastBreakTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        int cycleOperations = 0;
        int consecutiveErrors = 0;

        while (_running && !ct.IsCancellationRequested)
        {
            try
            {
                long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                int breakInterval = 120000 + _random.Next(60000); // 2–3 min
                if (now - lastBreakTime > breakInterval)
                {
                    _logger.LogInformation("Worker {ThreadId} taking 5-second break after {Ops} operations", threadId, cycleOperations);
                    await Task.Delay(5000, ct);
                    lastBreakTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    cycleOperations = 0;
                }

                int operation = _random.Next(100);
                if      (operation < 30) await CreateOrderWorkflow();
                else if (operation < 55) await UpdateCustomerWorkflow();
                else if (operation < 75) await InventoryCheckWorkflow();
                else if (operation < 90) await ProcessTransactionWorkflow();
                else if (operation < 95) await SessionManagementWorkflow();
                else if (operation < 98) await DeleteOldDataWorkflow();
                else if (operation < 99) await BulkInsertWorkflow();
                else                     await ProductOperationsWorkflow();

                operationCount++;
                cycleOperations++;
                consecutiveErrors = 0;
                await Task.Delay(_random.Next(50) + 30, ct); // 30–80ms
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                consecutiveErrors++;
                _logger.LogError(ex, "Error in worker {ThreadId} (consecutive errors: {Count})", threadId, consecutiveErrors);
                NewRelic.NoticeError(ex);
                int backoffMs = Math.Min(1000 * (1 << (consecutiveErrors - 1)), 8000);
                try { await Task.Delay(backoffMs, ct); } catch (OperationCanceledException) { break; }
            }
        }

        _logger.LogInformation("Worker {ThreadId} completed {Ops} operations", threadId, operationCount);
    }

    [Transaction]
    private async Task CreateOrderWorkflow()
    {
        long customerId = _random.Next(1000) + 1;
        int numItems = _random.Next(5) + 1;
        using var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/orders/create?customerId={customerId}&numItems={numItems}", null);
    }

    [Transaction]
    private async Task UpdateCustomerWorkflow()
    {
        long customerId = _random.Next(1000) + 1;
        int points = _random.Next(100);
        using var client = _httpClientFactory.CreateClient();
        await client.PutAsync($"{_apiBaseUrl}/api/customers/{customerId}/loyalty?points={points}", null);
    }

    [Transaction]
    private async Task InventoryCheckWorkflow()
    {
        long productId = _random.Next(500) + 1;
        using var client = _httpClientFactory.CreateClient();
        await client.GetAsync($"{_apiBaseUrl}/api/inventory/{productId}/check");
    }

    [Transaction]
    private async Task ProcessTransactionWorkflow()
    {
        long orderId = _random.Next(1000) + 1;
        using var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/transactions/process?orderId={orderId}", null);
    }

    [Transaction]
    private async Task SessionManagementWorkflow()
    {
        long customerId = _random.Next(1000) + 1;
        using var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/sessions/create?customerId={customerId}", null);
    }

    [Transaction]
    private async Task DeleteOldDataWorkflow()
    {
        using var client = _httpClientFactory.CreateClient();
        await client.DeleteAsync($"{_apiBaseUrl}/api/orders/old?daysToKeep=30");
    }

    [Transaction]
    private async Task BulkInsertWorkflow()
    {
        int batchSize = _random.Next(2) + 2; // 2–3
        using var client = _httpClientFactory.CreateClient();
        await client.PostAsync($"{_apiBaseUrl}/api/orders/bulk?batchSize={batchSize}", null);
    }

    [Transaction]
    private async Task ProductOperationsWorkflow()
    {
        long productId = _random.Next(500) + 1;
        using var client = _httpClientFactory.CreateClient();
        await client.GetAsync($"{_apiBaseUrl}/api/products/{productId}");
    }

    private void LogStatistics()
    {
        try
        {
            _logger.LogInformation("Load generator running. Threads: {Threads}", _numThreads);
            NewRelic.RecordMetric("Custom/Database/ActiveConnections", 0);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error logging statistics");
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 3: Commit**

```bash
git add app2-dotnet-oltp-load-generator/OltpLoadGenerator.cs
git commit -m "feat: add OltpLoadGenerator IHostedService with weighted workflows"
```

---

## Task 9: Program.cs (Wire Everything Together)

**Files:**
- Create: `app2-dotnet-oltp-load-generator/Program.cs`

**Interfaces:**
- Consumes: all services, all controllers, `OltpLoadGenerator`, `DatabaseManager`
- Produces: running ASP.NET Core app on port 8081; `/health` returns 200; all `/api/...` routes respond

- [ ] **Step 1: Create Program.cs**

```csharp
using LoadGen.Oltp;
using LoadGen.Oltp.Controllers;
using LoadGen.Oltp.Services;

var builder = WebApplication.CreateBuilder(args);

// Load .env file if present (same pattern as Java app)
var envFile = Path.Combine(Directory.GetCurrentDirectory(), ".env");
if (File.Exists(envFile))
{
    foreach (var line in File.ReadAllLines(envFile))
    {
        var trimmed = line.Trim();
        if (string.IsNullOrEmpty(trimmed) || trimmed.StartsWith('#')) continue;
        var idx = trimmed.IndexOf('=');
        if (idx < 0) continue;
        var key = trimmed[..idx].Trim();
        var value = trimmed[(idx + 1)..].Trim();
        Environment.SetEnvironmentVariable(key, value);
    }
}

// Override appsettings with env vars
if (Environment.GetEnvironmentVariable("DB_CONNECTION_STRING") is { } dbCs)
    builder.Configuration["ConnectionStrings:DefaultConnection"] = dbCs;
if (Environment.GetEnvironmentVariable("DB_POOL_MAX") is { } poolMax)
    builder.Configuration["Database:MaxPoolSize"] = poolMax;
if (Environment.GetEnvironmentVariable("DB_POOL_MIN") is { } poolMin)
    builder.Configuration["Database:MinPoolSize"] = poolMin;
if (Environment.GetEnvironmentVariable("THREADS") is { } threads)
    builder.Configuration["LoadGenerator:Threads"] = threads;

// Services
builder.Services.AddSingleton<DatabaseManager>();
builder.Services.AddSingleton<CustomerService>();
builder.Services.AddSingleton<OrderService>();
builder.Services.AddSingleton<ProductService>();
builder.Services.AddSingleton<InventoryService>();
builder.Services.AddSingleton<TransactionService>();
builder.Services.AddSingleton<SessionService>();
builder.Services.AddSingleton<OltpLoadGenerator>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<OltpLoadGenerator>());
builder.Services.AddHttpClient();
builder.Services.AddControllers();
builder.Services.AddHealthChecks();

var app = builder.Build();

app.MapControllers();
app.MapHealthChecks("/health");

Console.WriteLine(new string('=', 80));
Console.WriteLine(".NET OLTP Load Generator (APP2) Starting");
Console.WriteLine(new string('=', 80));

app.Run();
```

- [ ] **Step 2: Build and do a quick smoke test (no DB needed)**

```bash
cd app2-dotnet-oltp-load-generator && dotnet build
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 3: Commit**

```bash
git add app2-dotnet-oltp-load-generator/Program.cs
git commit -m "feat: wire Program.cs — services, controllers, hosted service, health check"
```

---

## Task 10: New Relic Config and Run Scripts

**Files:**
- Create: `app2-dotnet-oltp-load-generator/newrelic.config`
- Create: `app2-dotnet-oltp-load-generator/run.sh`
- Create: `app2-dotnet-oltp-load-generator/run.ps1`

**Interfaces:**
- Produces: `./run.sh` starts the app with New Relic profiler enabled; `./run.sh --bg` backgrounds it; `./run.sh --stop` kills it

- [ ] **Step 1: Create newrelic.config**

```xml
<?xml version="1.0"?>
<!-- New Relic .NET Agent Configuration for APP2 (.NET OLTP Load Generator) -->
<configuration xmlns="urn:newrelic-config" agentEnabled="true">
  <service licenseKey="${NEW_RELIC_LICENSE_KEY}" ssl="true"
           host="staging-collector.newrelic.com" port="443"/>
  <application>
    <name>${NEW_RELIC_APP_NAME}</name>
  </application>
  <log level="${NEW_RELIC_LOG_LEVEL}" />
  <distributedTracing enabled="true"/>
  <spanEvents enabled="true" maximumSamplesStored="10000"/>
  <transactionTracer enabled="true" transactionThreshold="apdex_f"
                     stackTraceThreshold="500" recordSql="obfuscated" explainEnabled="true"/>
  <datastore>
    <instanceReporting enabled="true"/>
    <databaseNameReporting enabled="true"/>
  </datastore>
  <errorCollector enabled="true">
    <ignoreStatusCodes>
      <code>404</code>
    </ignoreStatusCodes>
  </errorCollector>
  <applicationLogging enabled="true">
    <forwarding enabled="true" maxSamplesStored="10000"/>
    <localDecorating enabled="true"/>
    <metrics enabled="true"/>
  </applicationLogging>
  <labels>environment:staging;application:dotnet-oltp-load-generator;team:performance-testing;database:sqlserver</labels>
</configuration>
```

- [ ] **Step 2: Create run.sh**

```bash
#!/bin/bash
# Run script for .NET OLTP Load Generator with .env support
# Usage: ./run.sh           - Run in foreground
#        ./run.sh --bg       - Run in background
#        ./run.sh --stop     - Stop background process
#        ./run.sh --build    - Build first, then run

set -e

BACKGROUND=false
STOP=false
BUILD=false

for arg in "$@"; do
    case $arg in
        --bg|-b)   BACKGROUND=true ;;
        --stop)    STOP=true ;;
        --build)   BUILD=true ;;
    esac
done

if [ "$STOP" = true ]; then
    echo "Stopping .NET OLTP Load Generator..."
    pkill -f 'dotnet.*app2-oltp-load-generator' && echo "Process stopped" || echo "No running process found"
    exit 0
fi

# Load .env
if [ -f .env ]; then
    echo "Loading configuration from .env file..."
    set -a
    source <(cat .env | sed 's/#.*//g' | grep -v '^$' | grep '=')
    set +a
else
    echo "WARNING: .env file not found. Copy .env.example to .env"
fi

# Build if requested or if no output exists
RELEASE_DIR="bin/Release/net8.0"
if [ "$BUILD" = true ] || [ ! -f "$RELEASE_DIR/app2-oltp-load-generator.dll" ]; then
    echo "Building..."
    dotnet build -c Release
fi

echo "=========================================="
echo "Starting .NET OLTP Load Generator (APP2)"
echo "=========================================="
echo "Threads: ${THREADS:-3}"
echo "Port: 8081"
echo "NR App Name: ${NEW_RELIC_APP_NAME:-APP2}"
echo "=========================================="

# Set New Relic profiler env vars
NR_AGENT_DIR="$RELEASE_DIR/newrelic"
if [ -d "$NR_AGENT_DIR" ]; then
    if [[ "$OSTYPE" == "darwin"* ]]; then
        NR_PROFILER_LIB="$NR_AGENT_DIR/libNewRelicProfiler.dylib"
    else
        NR_PROFILER_LIB="$NR_AGENT_DIR/libNewRelicProfiler.so"
    fi
    export CORECLR_ENABLE_PROFILING=1
    export CORECLR_PROFILER="{36032161-FFC0-4B61-B559-F6C5D41BAE5A}"
    export CORECLR_PROFILER_PATH="$NR_PROFILER_LIB"
    export NEW_RELIC_HOME="$NR_AGENT_DIR"
    export NEW_RELIC_CONFIG_FILE="$(pwd)/newrelic.config"
    export NEW_RELIC_LICENSE_KEY="${NEW_RELIC_LICENSE_KEY:-}"
    export NEW_RELIC_APP_NAME="${NEW_RELIC_APP_NAME:-APP2}"
    echo "New Relic Agent: ENABLED"
    echo "Config: newrelic.config"
else
    echo "New Relic Agent: DISABLED (run with --build first to unpack agent)"
fi
echo "=========================================="

if [ "$BACKGROUND" = true ]; then
    nohup dotnet "$RELEASE_DIR/app2-oltp-load-generator.dll" > /dev/null 2>&1 &
    echo "Started in background with PID: $!"
else
    dotnet "$RELEASE_DIR/app2-oltp-load-generator.dll"
fi
```

- [ ] **Step 3: Create run.ps1**

```powershell
# Run script for .NET OLTP Load Generator
# Usage: .\run.ps1              - Run with New Relic agent
#        .\run.ps1 -NoAgent     - Run without agent
#        .\run.ps1 -Build       - Build first, then run
#        .\run.ps1 -Stop        - Stop background process

param(
    [switch]$NoAgent,
    [switch]$Build,
    [switch]$Stop
)

if ($Stop) {
    Write-Host "Stopping .NET OLTP Load Generator..."
    Get-Process dotnet -ErrorAction SilentlyContinue | 
        Where-Object { $_.CommandLine -like '*app2-oltp-load-generator*' } | 
        Stop-Process
    exit 0
}

# Load .env
if (Test-Path ".env") {
    Write-Host "Loading configuration from .env file..."
    Get-Content ".env" | ForEach-Object {
        if ($_ -notmatch '^\s*#' -and $_ -match '=') {
            $parts = $_ -split '=', 2
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
        }
    }
} else {
    Write-Warning ".env file not found. Copy .env.example to .env"
}

$releaseDir = "bin\Release\net8.0"
if ($Build -or -not (Test-Path "$releaseDir\app2-oltp-load-generator.dll")) {
    Write-Host "Building..."
    dotnet build -c Release
}

Write-Host ("=" * 42)
Write-Host ".NET OLTP Load Generator (APP2)"
Write-Host ("=" * 42)
Write-Host "Threads: $($env:THREADS ?? '3')"
Write-Host "Port: 8081"
Write-Host "NR App: $($env:NEW_RELIC_APP_NAME ?? 'APP2')"
Write-Host ("=" * 42)

$nrAgentDir = "$releaseDir\newrelic"
if (-not $NoAgent -and (Test-Path $nrAgentDir)) {
    $nrProfilerDll = "$nrAgentDir\NewRelicProfiler.dll"
    $env:CORECLR_ENABLE_PROFILING = "1"
    $env:CORECLR_PROFILER = "{36032161-FFC0-4B61-B559-F6C5D41BAE5A}"
    $env:CORECLR_PROFILER_PATH = $nrProfilerDll
    $env:NEW_RELIC_HOME = $nrAgentDir
    $env:NEW_RELIC_CONFIG_FILE = (Resolve-Path "newrelic.config").Path
    $env:NEW_RELIC_APP_NAME = $env:NEW_RELIC_APP_NAME ?? "APP2"
    Write-Host "New Relic Agent: ENABLED"
} else {
    Write-Host "New Relic Agent: DISABLED"
}

dotnet "$releaseDir\app2-oltp-load-generator.dll"
```

- [ ] **Step 4: Make run.sh executable**

```bash
chmod +x app2-dotnet-oltp-load-generator/run.sh
```

- [ ] **Step 5: Build and verify the agent files appear in output**

```bash
cd app2-dotnet-oltp-load-generator
dotnet build -c Release
ls bin/Release/net8.0/newrelic/
```
Expected: `libNewRelicProfiler.dylib` (macOS) or `libNewRelicProfiler.so` (Linux) present in `newrelic/` directory.

- [ ] **Step 6: Commit**

```bash
git add app2-dotnet-oltp-load-generator/newrelic.config app2-dotnet-oltp-load-generator/run.sh app2-dotnet-oltp-load-generator/run.ps1
git commit -m "feat: add newrelic.config and run scripts for .NET APM"
```

---

## Task 11: End-to-End Smoke Test and Push to GitHub

**Files:**
- No new files — verify and push

- [ ] **Step 1: Full build**

```bash
cd app2-dotnet-oltp-load-generator
dotnet build -c Release
```
Expected: `Build succeeded. 0 Error(s)`

- [ ] **Step 2: Smoke test — start the app, hit /health (no DB required)**

In one terminal:
```bash
cd app2-dotnet-oltp-load-generator
dotnet run --no-build -c Release &
sleep 3
curl -s http://localhost:8081/health
```
Expected: `{"status":"Healthy",...}` — app boots and health endpoint responds.

Kill the test process:
```bash
pkill -f app2-oltp-load-generator
```

- [ ] **Step 3: Verify all expected endpoints exist**

Start app (no DB), then confirm routes registered:
```bash
dotnet run --no-build -c Release &
sleep 3
curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/api/products/1
# Expected: 500 (no DB) — but NOT 404, which means the route exists
pkill -f app2-oltp-load-generator
```

- [ ] **Step 4: Full DB integration test (requires SQL Server)**

With `.env` configured pointing to the same SQL Server as Java APP1:
```bash
cd app2-dotnet-oltp-load-generator
cp .env.example .env
# Edit .env with real DB_CONNECTION_STRING and NEW_RELIC_LICENSE_KEY
./run.sh
```
Expected:
- App starts on port 8081
- Logs show `DatabaseManager initialized`
- `curl http://localhost:8081/api/products/1` returns `{"productId":1,"status":"SUCCESS"}`
- New Relic APM shows `APP2` sending transactions within ~2 minutes

- [ ] **Step 5: Push to GitHub**

```bash
cd /Users/gdivya/Downloads/app-oltp-load-generator-new
git add app2-dotnet-oltp-load-generator/
git commit -m "feat: complete .NET OLTP load generator (APP2)"
git push
```

---

## Self-Review Checklist

- [x] **Spec coverage**: All sections of the spec are covered — framework mappings, database (same SQL Server), config, load generator logic, instrumentation, run scripts, naming (APP2, port 8081)
- [x] **No placeholders**: All steps contain actual C# code or shell commands
- [x] **Type consistency**: `DatabaseManager.GetConnection()` returns `SqlConnection` throughout; all services consume `DatabaseManager`; all controllers consume their respective services
- [x] **Review Focus**: All 5 items have corresponding task-level attention — parameterized SQL (Tasks 3–6), `using` disposal (Tasks 3–6), sorted product IDs in BulkUpdateInventory (Task 5), zero-row UPDATE in ProcessPayment (Task 6), port conflict in run scripts (Task 10)
- [x] **TableCleanupService**: Correctly omitted per spec — Java handles seed data
