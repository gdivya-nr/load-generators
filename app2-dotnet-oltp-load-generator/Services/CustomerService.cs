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
