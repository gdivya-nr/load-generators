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
