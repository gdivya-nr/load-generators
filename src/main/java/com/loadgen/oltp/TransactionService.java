package com.loadgen.oltp;

import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.Random;
import java.util.UUID;

/**
 * Transaction Service - Handles payment transaction operations
 */
@Service
public class TransactionService {
    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);
    private final DatabaseManager dbManager;
    private final Random random = new Random();

    private static final String[] GATEWAYS = {"Stripe", "PayPal", "Square", "Authorize.Net", "Braintree"};
    private static final String[] TRANSACTION_TYPES = {"PAYMENT", "REFUND", "AUTHORIZATION", "CAPTURE"};

    public TransactionService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    @Trace
    public long createTransaction(long orderId, String transactionType) {
        String sql = "INSERT INTO oltp.TRANSACTIONS (" +
                     "order_id, transaction_type, payment_gateway, " +
                     "gateway_transaction_id, status, processed_at, amount, currency" +
                     ") VALUES (?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, " +
                     "(SELECT total_amount FROM oltp.ORDERS WHERE order_id = ?), 'USD')";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, new String[]{"transaction_id"})) {

            String gateway = GATEWAYS[random.nextInt(GATEWAYS.length)];
            String gatewayTxnId = UUID.randomUUID().toString();

            pstmt.setLong(1, orderId);
            pstmt.setString(2, transactionType);
            pstmt.setString(3, gateway);
            pstmt.setString(4, gatewayTxnId);
            pstmt.setLong(5, orderId);

            pstmt.executeUpdate();

            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long transactionId = rs.getLong(1);
                    logger.debug("Created transaction {} for order {} via {}", transactionId, orderId, gateway);
                    return transactionId;
                }
            }

        } catch (SQLException e) {
            logger.error("Error creating transaction for order {}", orderId, e);
            throw new RuntimeException(e);
        }

        throw new RuntimeException("Failed to create transaction");
    }

    @Trace
    public boolean processPayment(long orderId) {
        boolean success = random.nextInt(100) < 95;

        String sql = "UPDATE oltp.TRANSACTIONS SET status = ?, processed_at = CURRENT_TIMESTAMP, error_message = ? " +
                     "WHERE order_id = ? AND status = 'PENDING'";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            if (success) {
                pstmt.setString(1, "COMPLETED");
                pstmt.setNull(2, Types.VARCHAR);
            } else {
                pstmt.setString(1, "FAILED");
                pstmt.setString(2, "Payment declined - insufficient funds");
            }
            pstmt.setLong(3, orderId);

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Processed payment for order {}: {}", orderId, success ? "SUCCESS" : "FAILED");
            }

        } catch (SQLException e) {
            logger.error("Error processing payment for order {}", orderId, e);
            return false;
        }

        return success;
    }

    @Trace
    public void refundTransaction(long transactionId) {
        String sql = "INSERT INTO oltp.TRANSACTIONS (" +
                     "order_id, transaction_type, payment_gateway, " +
                     "gateway_transaction_id, status, processed_at, amount, currency" +
                     ") SELECT order_id, 'REFUND', payment_gateway, " +
                     "?, 'COMPLETED', CURRENT_TIMESTAMP, -amount, currency " +
                     "FROM oltp.TRANSACTIONS WHERE transaction_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String refundId = "REFUND-" + UUID.randomUUID().toString();
            pstmt.setString(1, refundId);
            pstmt.setLong(2, transactionId);

            int inserted = pstmt.executeUpdate();
            if (inserted > 0) {
                logger.debug("Created refund for transaction {}", transactionId);
            }

        } catch (SQLException e) {
            logger.error("Error creating refund for transaction {}", transactionId, e);
            throw new RuntimeException(e);
        }
    }

    @Trace
    public double getTotalTransactionAmount(long customerId) {
        String sql = "SELECT SUM(t.amount) as total " +
                     "FROM oltp.TRANSACTIONS t " +
                     "JOIN oltp.ORDERS o ON t.order_id = o.order_id " +
                     "WHERE o.customer_id = ? AND t.status = 'COMPLETED'";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, customerId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    double total = rs.getDouble("total");
                    logger.debug("Total transaction amount for customer {}: ${}", customerId, total);
                    return total;
                }
            }

        } catch (SQLException e) {
            logger.error("Error getting total transaction amount for customer {}", customerId, e);
        }

        return 0.0;
    }

    @Trace
    public int getFailedTransactionCount() {
        String sql = "SELECT COUNT(*) FROM oltp.TRANSACTIONS WHERE status = 'FAILED'";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            logger.error("Error getting failed transaction count", e);
        }

        return 0;
    }
}
