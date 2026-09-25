package com.loadgen.oltp;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Database Connection Manager using HikariCP
 */
@Component
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private HikariDataSource dataSource;

    @Value("${db.url}")
    private String dbUrl;

    @Value("${db.username}")
    private String dbUsername;

    @Value("${db.password}")
    private String dbPassword;

    @Value("${db.pool.max:30}")
    private int maxPoolSize;

    @Value("${db.pool.min:10}")
    private int minIdle;

    @PostConstruct
    public void initialize() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUsername);
        config.setPassword(dbPassword);

        // Connection pool settings for high load
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        // CRITICAL: Prevent threads from hanging indefinitely
        config.setValidationTimeout(5000);  // 5 second validation timeout
        config.setLeakDetectionThreshold(60000);  // Detect connection leaks after 60 seconds

        // Performance settings
        config.setAutoCommit(true);

        // SQL Server specific settings - TIMEOUTS ARE CRITICAL
        config.addDataSourceProperty("socketTimeout", "60000");   // 60s socket read timeout
        config.addDataSourceProperty("loginTimeout", "30");        // 30s connection login timeout (seconds)
        config.addDataSourceProperty("sendStringParametersAsUnicode", "false"); // perf: avoid N'' params unless needed

        config.setPoolName("OltpConnectionPool");

        this.dataSource = new HikariDataSource(config);
        logger.info("Database connection pool initialized: {} (max: {}, min: {})",
                    config.getJdbcUrl(), maxPoolSize, minIdle);
    }

    public Connection getConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        // Set default query timeout for all statements created from this connection
        // This prevents individual queries from hanging indefinitely
        try {
            conn.setNetworkTimeout(null, 60000); // 60 second timeout for all operations
        } catch (SQLException e) {
            // Some JDBC drivers don't support this, log but continue
            logger.debug("Could not set network timeout: {}", e.getMessage());
        }
        return conn;
    }

    public int getActiveConnections() {
        return dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    public int getIdleConnections() {
        return dataSource.getHikariPoolMXBean().getIdleConnections();
    }

    public int getTotalConnections() {
        return dataSource.getHikariPoolMXBean().getTotalConnections();
    }

    @PreDestroy
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection pool closed");
        }
    }
}
