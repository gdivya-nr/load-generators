using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class TransactionService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<TransactionService> _logger;

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
        string gateway = Gateways[Random.Shared.Next(Gateways.Length)];
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
        bool success = Random.Shared.Next(100) < 95;
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
