package com.loadgen.oltp;

import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.Random;

/**
 * Order Service - Handles order-related operations
 */
@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final DatabaseManager dbManager;
    private final Random random = new Random();

    private static final String[] STATUSES = {"PENDING", "PROCESSING", "COMPLETED", "SHIPPED", "DELIVERED"};
    private static final String[] PAYMENT_METHODS = {"CREDIT_CARD", "DEBIT_CARD", "PAYPAL", "BANK_TRANSFER"};

    public OrderService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    @Trace
    public long createOrder(long customerId) {
        String sql = "INSERT INTO oltp.ORDERS (customer_id, order_date, status, payment_method, created_at) " +
                     "VALUES (?, CURRENT_TIMESTAMP, 'PENDING', ?, CURRENT_TIMESTAMP)";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, new String[]{"order_id"})) {

            pstmt.setLong(1, customerId);
            pstmt.setString(2, PAYMENT_METHODS[random.nextInt(PAYMENT_METHODS.length)]);

            pstmt.executeUpdate();

            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long orderId = rs.getLong(1);
                    logger.debug("Created order {} for customer {}", orderId, customerId);
                    return orderId;
                }
            }

        } catch (SQLException e) {
            logger.error("Error creating order for customer {}", customerId, e);
            throw new RuntimeException(e);
        }

        throw new RuntimeException("Failed to create order");
    }

    @Trace
    public void addOrderItem(long orderId, long productId, int quantity) {
        String priceQuery = "SELECT price FROM oltp.PRODUCTS WHERE product_id = ?";
        String insertSql = "INSERT INTO oltp.ORDER_ITEMS (order_id, product_id, quantity, unit_price, subtotal) " +
                          "VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = dbManager.getConnection()) {
            double price;

            try (PreparedStatement pstmt = conn.prepareStatement(priceQuery)) {
                pstmt.setLong(1, productId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        price = rs.getDouble("price");
                    } else {
                        logger.warn("Product {} not found", productId);
                        return;
                    }
                }
            }

            double subtotal = price * quantity;
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setLong(1, orderId);
                pstmt.setLong(2, productId);
                pstmt.setInt(3, quantity);
                pstmt.setDouble(4, price);
                pstmt.setDouble(5, subtotal);

                pstmt.executeUpdate();
                logger.debug("Added item to order {}: product={}, qty={}", orderId, productId, quantity);
            }

        } catch (SQLException e) {
            logger.error("Error adding item to order {}", orderId, e);
            throw new RuntimeException(e);
        }
    }

    @Trace
    public void calculateOrderTotal(long orderId) {
        String sql = "UPDATE oltp.ORDERS SET total_amount = (" +
                     "  SELECT SUM(subtotal) FROM oltp.ORDER_ITEMS WHERE order_id = ?" +
                     "), tax_amount = (" +
                     "  SELECT SUM(subtotal) * 0.08 FROM oltp.ORDER_ITEMS WHERE order_id = ?" +
                     "), updated_at = CURRENT_TIMESTAMP " +
                     "WHERE order_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, orderId);
            pstmt.setLong(2, orderId);
            pstmt.setLong(3, orderId);

            pstmt.executeUpdate();
            logger.debug("Calculated total for order {}", orderId);

        } catch (SQLException e) {
            logger.error("Error calculating order total for {}", orderId, e);
            throw new RuntimeException(e);
        }
    }

    @Trace
    public void updateOrderStatus(long orderId, String status) {
        String sql = "UPDATE oltp.ORDERS SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, status);
            pstmt.setLong(2, orderId);

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Updated order {} status to {}", orderId, status);
            }

        } catch (SQLException e) {
            logger.error("Error updating order status for {}", orderId, e);
            throw new RuntimeException(e);
        }
    }

    @Trace
    public void logAudit(String tableName, long recordId, String operation) {
        String sql = "INSERT INTO oltp.AUDIT_LOG (table_name, operation, record_id, changed_by, changed_at) " +
                     "VALUES (?, ?, ?, 'SYSTEM', CURRENT_TIMESTAMP)";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, tableName);
            pstmt.setString(2, operation);
            pstmt.setLong(3, recordId);

            pstmt.executeUpdate();

        } catch (SQLException e) {
            logger.error("Error logging audit", e);
            // Don't throw - audit logging should not break main flow
        }
    }

    @Trace
    public long getMaxOrderId() {
        String sql = "SELECT ISNULL(MAX(order_id), 0) FROM oltp.ORDERS";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }

        } catch (SQLException e) {
            logger.error("Error getting max order id", e);
        }

        return 0;
    }

    @Trace
    public int getOrderCount() {
        String sql = "SELECT COUNT(*) FROM oltp.ORDERS";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            logger.error("Error getting order count", e);
        }

        return 0;
    }

    @Trace
    public int deleteOldCompletedOrders(int daysToKeep) {
        String deleteItemsSql = "DELETE FROM oltp.ORDER_ITEMS WHERE order_id IN (" +
                               "  SELECT order_id FROM oltp.ORDERS " +
                               "  WHERE status IN ('COMPLETED', 'DELIVERED') " +
                               "  AND order_date < DATEADD(day, -?, GETDATE())" +
                               ")";

        String deleteTransactionsSql = "DELETE FROM oltp.TRANSACTIONS WHERE order_id IN (" +
                                      "  SELECT order_id FROM oltp.ORDERS " +
                                      "  WHERE status IN ('COMPLETED', 'DELIVERED') " +
                                      "  AND order_date < DATEADD(day, -?, GETDATE())" +
                                      ")";

        String deleteOrdersSql = "DELETE FROM oltp.ORDERS " +
                                "WHERE status IN ('COMPLETED', 'DELIVERED') " +
                                "AND order_date < DATEADD(day, -?, GETDATE())";

        int totalDeleted = 0;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (PreparedStatement pstmt = conn.prepareStatement(deleteItemsSql)) {
                    pstmt.setInt(1, daysToKeep);
                    int itemsDeleted = pstmt.executeUpdate();
                    logger.debug("Deleted {} old order items", itemsDeleted);
                }

                try (PreparedStatement pstmt = conn.prepareStatement(deleteTransactionsSql)) {
                    pstmt.setInt(1, daysToKeep);
                    int txnDeleted = pstmt.executeUpdate();
                    logger.debug("Deleted {} old transactions", txnDeleted);
                }

                try (PreparedStatement pstmt = conn.prepareStatement(deleteOrdersSql)) {
                    pstmt.setInt(1, daysToKeep);
                    totalDeleted = pstmt.executeUpdate();
                    logger.debug("Deleted {} old completed orders (older than {} days)", totalDeleted, daysToKeep);
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            logger.error("Error deleting old completed orders", e);
        }

        return totalDeleted;
    }

    @Trace
    public int deleteCancelledOrders() {
        String deleteItemsSql = "DELETE FROM oltp.ORDER_ITEMS WHERE order_id IN (" +
                               "  SELECT order_id FROM oltp.ORDERS WHERE status IN ('CANCELLED', 'PAYMENT_FAILED')" +
                               ")";

        String deleteTransactionsSql = "DELETE FROM oltp.TRANSACTIONS WHERE order_id IN (" +
                                      "  SELECT order_id FROM oltp.ORDERS WHERE status IN ('CANCELLED', 'PAYMENT_FAILED')" +
                                      ")";

        String deleteOrdersSql = "DELETE FROM oltp.ORDERS WHERE status IN ('CANCELLED', 'PAYMENT_FAILED')";

        int totalDeleted = 0;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (PreparedStatement pstmt = conn.prepareStatement(deleteItemsSql)) {
                    pstmt.executeUpdate();
                }

                try (PreparedStatement pstmt = conn.prepareStatement(deleteTransactionsSql)) {
                    pstmt.executeUpdate();
                }

                try (PreparedStatement pstmt = conn.prepareStatement(deleteOrdersSql)) {
                    totalDeleted = pstmt.executeUpdate();
                    if (totalDeleted > 0) {
                        logger.debug("Deleted {} cancelled/failed orders", totalDeleted);
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            logger.error("Error deleting cancelled orders", e);
        }

        return totalDeleted;
    }

    @Trace
    public int deleteOldAuditLogs(int daysToKeep) {
        String sql = "DELETE FROM oltp.AUDIT_LOG WHERE changed_at < DATEADD(day, -?, GETDATE())";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, daysToKeep);
            int deleted = pstmt.executeUpdate();

            if (deleted > 0) {
                logger.debug("Deleted {} old audit log entries (older than {} days)", deleted, daysToKeep);
            }

            return deleted;

        } catch (SQLException e) {
            logger.error("Error deleting old audit logs", e);
        }

        return 0;
    }
}
