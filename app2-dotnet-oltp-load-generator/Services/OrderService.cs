using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class OrderService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<OrderService> _logger;

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
        cmd.Parameters.AddWithValue("@paymentMethod", PaymentMethods[Random.Shared.Next(PaymentMethods.Length)]);
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
