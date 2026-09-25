package com.loadgen.oltp;

import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

/**
 * Product Service - Handles product-related operations
 */
@Service
public class ProductService {
    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);
    private final DatabaseManager dbManager;
    private final Random random = new Random();

    public ProductService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    @Trace
    public void updatePrice(long productId) {
        // Simulate price adjustment (up or down by up to 5%)
        String sql = "UPDATE oltp.PRODUCTS SET price = price * (1 + (? / 100.0)) WHERE product_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            double priceChange = (random.nextDouble() * 10) - 5; // -5% to +5%
            pstmt.setDouble(1, priceChange);
            pstmt.setLong(2, productId);

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Updated price for product {}: {}%", productId, String.format("%.2f", priceChange));
            }

        } catch (SQLException e) {
            logger.error("Error updating price for product {}", productId, e);
            throw new RuntimeException(e);
        }
    }

    @Trace
    public String getProductDetails(long productId) {
        String sql = "SELECT product_name, category, price, sku FROM oltp.PRODUCTS WHERE product_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, productId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String details = String.format("%s (%s) - $%.2f [%s]",
                            rs.getString("product_name"),
                            rs.getString("category"),
                            rs.getDouble("price"),
                            rs.getString("sku"));
                    logger.debug("Product details: {}", details);
                    return details;
                }
            }

        } catch (SQLException e) {
            logger.error("Error getting product details for {}", productId, e);
        }

        return null;
    }

    @Trace
    public void searchByCategory() {
        // Get random category
        int categoryNum = random.nextInt(10);
        String category = "Category" + categoryNum;

        String sql = "SELECT product_id, product_name, price FROM oltp.PRODUCTS WHERE category = ? AND is_active = 1 ORDER BY price DESC";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setMaxRows(20); // Limit results

            try (ResultSet rs = pstmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                logger.debug("Found {} products in category {}", count, category);
            }

        } catch (SQLException e) {
            logger.error("Error searching products by category {}", category, e);
        }
    }

    @Trace
    public int getProductCount() {
        String sql = "SELECT COUNT(*) FROM oltp.PRODUCTS WHERE is_active = 1";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            logger.error("Error getting product count", e);
        }

        return 0;
    }

    @Trace
    public void deactivateProduct(long productId) {
        String sql = "UPDATE oltp.PRODUCTS SET is_active = 0 WHERE product_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, productId);
            pstmt.executeUpdate();

            logger.debug("Deactivated product {}", productId);

        } catch (SQLException e) {
            logger.error("Error deactivating product {}", productId, e);
        }
    }
}
