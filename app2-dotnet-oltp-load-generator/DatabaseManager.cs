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
