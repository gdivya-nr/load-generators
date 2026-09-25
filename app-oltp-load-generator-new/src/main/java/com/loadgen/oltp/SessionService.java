package com.loadgen.oltp;

import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Session Service - Handles user session operations
 */
@Service
public class SessionService {
    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);
    private final DatabaseManager dbManager;

    public SessionService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    @Trace
    public String createSession(long customerId) {
        String sessionId = UUID.randomUUID().toString();
        String sql = "INSERT INTO oltp.SESSION_DATA (session_id, customer_id, login_time, last_activity, is_active) " +
                     "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, sessionId);
            pstmt.setLong(2, customerId);

            pstmt.executeUpdate();
            logger.debug("Created session {} for customer {}", sessionId, customerId);

        } catch (SQLException e) {
            logger.error("Error creating session for customer {}", customerId, e);
            throw new RuntimeException(e);
        }

        return sessionId;
    }

    @Trace
    public void updateSessionActivity(String sessionId) {
        String sql = "UPDATE oltp.SESSION_DATA SET last_activity = CURRENT_TIMESTAMP WHERE session_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, sessionId);

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Updated session activity: {}", sessionId);
            }

        } catch (SQLException e) {
            logger.error("Error updating session activity for {}", sessionId, e);
        }
    }

    @Trace
    public void expireSession(String sessionId) {
        String sql = "UPDATE oltp.SESSION_DATA SET is_active = 0 WHERE session_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, sessionId);

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Expired session: {}", sessionId);
            }

        } catch (SQLException e) {
            logger.error("Error expiring session {}", sessionId, e);
        }
    }

    @Trace
    public void expireOldSessions() {
        // Expire sessions older than 1 hour
        String sql = "UPDATE oltp.SESSION_DATA SET is_active = 0 " +
                     "WHERE is_active = 1 AND last_activity < DATEADD(hour, -1, GETDATE())";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                logger.debug("Expired {} old sessions", updated);
            }

        } catch (SQLException e) {
            logger.error("Error expiring old sessions", e);
        }
    }

    @Trace
    public int getActiveSessionCount() {
        String sql = "SELECT COUNT(*) FROM oltp.SESSION_DATA WHERE is_active = 1";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            logger.error("Error getting active session count", e);
        }

        return 0;
    }

    @Trace
    public int getSessionCountByCustomer(long customerId) {
        String sql = "SELECT COUNT(*) FROM oltp.SESSION_DATA WHERE customer_id = ? AND is_active = 1";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, customerId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }

        } catch (SQLException e) {
            logger.error("Error getting session count for customer {}", customerId, e);
        }

        return 0;
    }

    @Trace
    public int deleteExpiredSessions() {
        // Delete expired sessions (older than 2 hours)
        String sql = "DELETE FROM oltp.SESSION_DATA " +
                     "WHERE is_active = 0 OR last_activity < DATEADD(hour, -2, GETDATE())";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                logger.debug("Deleted {} expired sessions", deleted);
            }

            return deleted;

        } catch (SQLException e) {
            logger.error("Error deleting expired sessions", e);
        }

        return 0;
    }
}
