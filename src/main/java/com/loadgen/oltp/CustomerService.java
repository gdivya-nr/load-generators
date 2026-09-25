package com.loadgen.oltp;

import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Customer Service - Handles customer-related operations
 */
@Service
public class CustomerService {
    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);
    private final DatabaseManager dbManager;

    public CustomerService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    @Trace
    public void updateLoyaltyPoints(long customerId, int points) {
        String sql = "UPDATE oltp.CUSTOMERS SET loyalty_points = loyalty_points + ?, updated_at = CURRENT_TIMESTAMP WHERE customer_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, points);
            pstmt.setLong(2, customerId);

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Updated loyalty points for customer {}: +{} points", customerId, points);
            }

        } catch (SQLException e) {
            logger.error("Error updating loyalty points for customer {}", customerId, e);
            throw new RuntimeException(e);
        }
    }

    @Trace
    public void upgradeCustomerType(long customerId) {
        String sql = "UPDATE oltp.CUSTOMERS SET customer_type = 'PREMIUM', updated_at = CURRENT_TIMESTAMP WHERE customer_id = ? AND customer_type = 'REGULAR'";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, customerId);

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Upgraded customer {} to PREMIUM", customerId);
            }

        } catch (SQLException e) {
            logger.error("Error upgrading customer type for {}", customerId, e);
            throw new RuntimeException(e);
        }
    }

    @Trace
    public void logCustomerAccess(long customerId) {
        String sql = "INSERT INTO oltp.AUDIT_LOG (table_name, operation, record_id, changed_by, changed_at) " +
                     "VALUES ('CUSTOMERS', 'ACCESS', ?, 'SYSTEM', CURRENT_TIMESTAMP)";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, customerId);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            logger.error("Error logging customer access for {}", customerId, e);
            // Don't throw - audit logging should not break main flow
        }
    }

    @Trace
    public String getCustomerEmail(long customerId) {
        String sql = "SELECT email FROM oltp.CUSTOMERS WHERE customer_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, customerId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("email");
                }
            }

        } catch (SQLException e) {
            logger.error("Error getting customer email for {}", customerId, e);
        }

        return null;
    }

    @Trace
    public int getCustomerCount() {
        String sql = "SELECT COUNT(*) FROM oltp.CUSTOMERS";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            logger.error("Error getting customer count", e);
        }

        return 0;
    }
}
