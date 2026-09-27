using Microsoft.Data.SqlClient;
using NewRelic.Api.Agent;

namespace LoadGen.Oltp.Services;

public class ProductService
{
    private readonly DatabaseManager _db;
    private readonly ILogger<ProductService> _logger;

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
        double priceChange = (Random.Shared.NextDouble() * 10) - 5; // -5% to +5%
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
                var details = $"{reader["product_name"]} ({reader["category"]}) - {reader["price"]} [{reader["sku"]}]";
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
        string category = $"Category{Random.Shared.Next(10)}";
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
