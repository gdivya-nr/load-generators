package com.loadgen.oltp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Service to clean up (truncate) all tables before starting load generation
 */
@Service
public class TableCleanupService {
    private static final Logger logger = LoggerFactory.getLogger(TableCleanupService.class);
    private final DatabaseManager dbManager;

    // Tables to truncate in FK-safe order (children before parents)
    private static final String[] TABLES_TO_TRUNCATE = {
        "oltp.ORDER_ITEMS",
        "oltp.TRANSACTIONS",
        "oltp.ORDERS",
        "oltp.SESSION_DATA",
        "oltp.AUDIT_LOG",
        "oltp.INVENTORY",
        "oltp.CUSTOMERS",
        "oltp.PRODUCTS"
    };

    public TableCleanupService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void truncateAndRebuild() {
        truncateAllTables();
        repopulateSeedData();
    }

    public void truncateAllTables() {
        logger.info("=".repeat(80));
        logger.info("Starting table cleanup - truncating all tables...");
        logger.info("=".repeat(80));

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {

            for (String table : TABLES_TO_TRUNCATE) {
                stmt.execute("DELETE FROM " + table);
                logger.info("✓ Cleared table: {}", table);
            }

            logger.info("=".repeat(80));
            logger.info("Table cleanup completed: all {} tables truncated successfully", TABLES_TO_TRUNCATE.length);
            logger.info("=".repeat(80));

        } catch (SQLException e) {
            logger.error("Error during table cleanup", e);
            throw new RuntimeException("Failed to clean up tables", e);
        }
    }

    public void repopulateSeedData() {
        logger.info("=".repeat(80));
        logger.info("Repopulating seed data...");
        logger.info("=".repeat(80));

        insertCustomers();
        insertProducts();
        insertInventory();

        logger.info("=".repeat(80));
        logger.info("Seed data repopulation completed successfully!");
        logger.info("=".repeat(80));
    }

    private void insertCustomers() {
        logger.info("Inserting 1000 customers...");
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET IDENTITY_INSERT oltp.CUSTOMERS ON");
                }
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO oltp.CUSTOMERS (customer_id, first_name, last_name, email, phone, city, state, country, customer_type, loyalty_points) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'USA', ?, ?)")) {

                    for (int i = 1; i <= 1000; i++) {
                        pstmt.setInt(1, i);
                        pstmt.setString(2, "FirstName" + i);
                        pstmt.setString(3, "LastName" + i);
                        pstmt.setString(4, "customer" + i + "@example.com");
                        pstmt.setString(5, "555-" + String.format("%07d", i));
                        pstmt.setString(6, "City" + (i % 50));
                        pstmt.setString(7, "State" + (i % 50));
                        pstmt.setString(8, i % 10 == 0 ? "PREMIUM" : "REGULAR");
                        pstmt.setInt(9, (i * 100) % 10000);
                        pstmt.addBatch();
                        if (i % 200 == 0) pstmt.executeBatch();
                    }
                    pstmt.executeBatch();
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET IDENTITY_INSERT oltp.CUSTOMERS OFF");
                }
                conn.commit();
                logger.info("✓ Inserted 1000 customers");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Error inserting customers", e);
            throw new RuntimeException(e);
        }
    }

    private void insertProducts() {
        logger.info("Inserting 500 products...");
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET IDENTITY_INSERT oltp.PRODUCTS ON");
                }
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO oltp.PRODUCTS (product_id, product_name, category, subcategory, price, cost, sku, is_active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 1)")) {

                    for (int i = 1; i <= 500; i++) {
                        pstmt.setInt(1, i);
                        pstmt.setString(2, "Product " + i);
                        pstmt.setString(3, "Category" + (i % 10));
                        pstmt.setString(4, "SubCat" + (i % 20));
                        pstmt.setDouble(5, 19.99 + (i * 1.5));
                        pstmt.setDouble(6, 10.00 + (i * 0.75));
                        pstmt.setString(7, String.format("SKU-%08d", i));
                        pstmt.addBatch();
                        if (i % 200 == 0) pstmt.executeBatch();
                    }
                    pstmt.executeBatch();
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET IDENTITY_INSERT oltp.PRODUCTS OFF");
                }
                conn.commit();
                logger.info("✓ Inserted 500 products");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Error inserting products", e);
            throw new RuntimeException(e);
        }
    }

    private void insertInventory() {
        logger.info("Inserting 500 inventory records...");
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET IDENTITY_INSERT oltp.INVENTORY ON");
                }
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO oltp.INVENTORY (inventory_id, product_id, warehouse_location, quantity_available, reorder_level) " +
                        "VALUES (?, ?, ?, ?, 100)")) {

                    for (int i = 1; i <= 500; i++) {
                        pstmt.setInt(1, i);
                        pstmt.setInt(2, i);
                        pstmt.setString(3, "WH-" + (i % 5));
                        pstmt.setInt(4, 1000 + (i * 137) % 5000);
                        pstmt.addBatch();
                        if (i % 200 == 0) pstmt.executeBatch();
                    }
                    pstmt.executeBatch();
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET IDENTITY_INSERT oltp.INVENTORY OFF");
                }
                conn.commit();
                logger.info("✓ Inserted 500 inventory records");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Error inserting inventory", e);
            throw new RuntimeException(e);
        }
    }
}
