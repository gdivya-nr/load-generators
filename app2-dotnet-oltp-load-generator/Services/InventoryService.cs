using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class InventoryService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<InventoryService> _logger;

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
            int available = (result == null || result == DBNull.Value) ? 0 : Convert.ToInt32(result);
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
        string newLocation = Warehouses[Random.Shared.Next(Warehouses.Length)];
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
        int updateCount = Random.Shared.Next(20) + 10; // 10–30 products

        // CRITICAL: Sort product IDs ascending to prevent deadlocks (matches Java lock ordering)
        var productIds = Enumerable.Range(0, updateCount)
            .Select(_ => (long)(Random.Shared.Next(500) + 1))
            .Distinct()
            .OrderBy(id => id)
            .ToList();

        var adjustments = productIds.ToDictionary(
            id => id,
            _ => Random.Shared.Next(100) - 50); // -50 to +50

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
