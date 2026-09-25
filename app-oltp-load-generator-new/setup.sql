-- SQL Server Database Setup Script for Load Testing
-- Run as a DBA login (sa) with CREATE DATABASE / CREATE SCHEMA permissions

-- ==================================================
-- STEP 0: Create the database
-- ==================================================

IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'loadtest3')
    CREATE DATABASE loadtest3;
GO

USE loadtest3;
GO

-- ==================================================
-- STEP 1: Create schemas
-- ==================================================

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'oltp')
    EXEC('CREATE SCHEMA oltp');
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'analytics')
    EXEC('CREATE SCHEMA analytics');
GO

-- ==================================================
-- STEP 2: Create the 'sqlserver' login + grant monitoring access to ALL databases
-- (Single account used by both the load-generator app and the OTel collector)
-- ==================================================

USE master;
GO

-- Create the server login
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'sqlserver')
    CREATE LOGIN sqlserver WITH PASSWORD = 'sqlserver', CHECK_POLICY = OFF;
GO

-- Server-level monitoring permissions (for OTel sqlserver receiver / New Relic DB monitoring)
GRANT VIEW SERVER STATE   TO sqlserver;
GRANT VIEW ANY DEFINITION TO sqlserver;
GRANT VIEW ANY DATABASE   TO sqlserver;
GO

-- Loop through every online database and grant per-DB monitoring permissions
DECLARE @db sysname, @sql NVARCHAR(MAX);

DECLARE db_cursor CURSOR LOCAL FAST_FORWARD FOR
    SELECT name
    FROM sys.databases
    WHERE state = 0                       -- online only
      AND name NOT IN ('tempdb')          -- tempdb is recreated on restart
      AND HAS_DBACCESS(name) = 1;         -- skip DBs we can't access

OPEN db_cursor;
FETCH NEXT FROM db_cursor INTO @db;

WHILE @@FETCH_STATUS = 0
BEGIN
    SET @sql = N'
        USE [' + @db + N'];
        IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = ''sqlserver'')
            CREATE USER sqlserver FOR LOGIN sqlserver;
        GRANT VIEW DATABASE STATE TO sqlserver;
        ALTER ROLE db_datareader ADD MEMBER sqlserver;
    ';
    EXEC sp_executesql @sql;
    FETCH NEXT FROM db_cursor INTO @db;
END

CLOSE db_cursor;
DEALLOCATE db_cursor;
GO

-- App-level DML grants for the load-generator (loadtest3 DB only)
USE loadtest3;
GO

GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::oltp      TO sqlserver;
GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::analytics TO sqlserver;

-- Allow TRUNCATE/DELETE-then-IDENTITY_INSERT used by TableCleanupService
GRANT ALTER ON SCHEMA::oltp      TO sqlserver;
GRANT ALTER ON SCHEMA::analytics TO sqlserver;
GO

-- ==================================================
-- STEP 3: Drop existing OLTP tables (children first to respect FKs)
-- ==================================================

IF OBJECT_ID('oltp.ORDER_ITEMS',  'U') IS NOT NULL DROP TABLE oltp.ORDER_ITEMS;
IF OBJECT_ID('oltp.TRANSACTIONS', 'U') IS NOT NULL DROP TABLE oltp.TRANSACTIONS;
IF OBJECT_ID('oltp.INVENTORY',    'U') IS NOT NULL DROP TABLE oltp.INVENTORY;
IF OBJECT_ID('oltp.ORDERS',       'U') IS NOT NULL DROP TABLE oltp.ORDERS;
IF OBJECT_ID('oltp.AUDIT_LOG',    'U') IS NOT NULL DROP TABLE oltp.AUDIT_LOG;
IF OBJECT_ID('oltp.SESSION_DATA', 'U') IS NOT NULL DROP TABLE oltp.SESSION_DATA;
IF OBJECT_ID('oltp.CUSTOMERS',    'U') IS NOT NULL DROP TABLE oltp.CUSTOMERS;
IF OBJECT_ID('oltp.PRODUCTS',     'U') IS NOT NULL DROP TABLE oltp.PRODUCTS;
GO

-- ==================================================
-- STEP 4: Create OLTP tables
-- ==================================================

-- Customers Table
CREATE TABLE oltp.CUSTOMERS (
    customer_id     BIGINT IDENTITY(1,1) PRIMARY KEY,
    first_name      NVARCHAR(100) NOT NULL,
    last_name       NVARCHAR(100) NOT NULL,
    email           NVARCHAR(255) NOT NULL UNIQUE,
    phone           NVARCHAR(20),
    address         NVARCHAR(500),
    city            NVARCHAR(100),
    state           NVARCHAR(50),
    zip_code        NVARCHAR(20),
    country         NVARCHAR(100),
    created_at      DATETIME2 DEFAULT GETDATE(),
    updated_at      DATETIME2 DEFAULT GETDATE(),
    loyalty_points  INT DEFAULT 0,
    customer_type   NVARCHAR(20) DEFAULT 'REGULAR'
);

CREATE INDEX idx_customer_type    ON oltp.CUSTOMERS (customer_type);
CREATE INDEX idx_customer_created ON oltp.CUSTOMERS (created_at);
GO

-- Products Table
CREATE TABLE oltp.PRODUCTS (
    product_id   BIGINT IDENTITY(1,1) PRIMARY KEY,
    product_name NVARCHAR(200) NOT NULL,
    category     NVARCHAR(100),
    subcategory  NVARCHAR(100),
    description  NVARCHAR(1000),
    price        DECIMAL(10,2) NOT NULL,
    cost         DECIMAL(10,2),
    weight       DECIMAL(10,3),
    dimensions   NVARCHAR(100),
    manufacturer NVARCHAR(200),
    sku          NVARCHAR(100) UNIQUE,
    created_at   DATETIME2 DEFAULT GETDATE(),
    is_active    BIT DEFAULT 1
);

CREATE INDEX idx_product_category ON oltp.PRODUCTS (category);
CREATE INDEX idx_product_active   ON oltp.PRODUCTS (is_active);
GO

-- Inventory Table
CREATE TABLE oltp.INVENTORY (
    inventory_id       BIGINT IDENTITY(1,1) PRIMARY KEY,
    product_id         BIGINT NOT NULL,
    warehouse_location NVARCHAR(100),
    quantity_available INT NOT NULL,
    quantity_reserved  INT DEFAULT 0,
    reorder_level      INT,
    last_restock_date  DATETIME2,
    updated_at         DATETIME2 DEFAULT GETDATE(),
    CONSTRAINT fk_inv_product FOREIGN KEY (product_id) REFERENCES oltp.PRODUCTS (product_id)
);

CREATE INDEX idx_inv_product  ON oltp.INVENTORY (product_id);
CREATE INDEX idx_inv_location ON oltp.INVENTORY (warehouse_location);
GO

-- Orders Table
CREATE TABLE oltp.ORDERS (
    order_id         BIGINT IDENTITY(1,1) PRIMARY KEY,
    customer_id      BIGINT NOT NULL,
    order_date       DATETIME2 DEFAULT GETDATE(),
    status           NVARCHAR(50) NOT NULL,
    total_amount     DECIMAL(12,2),
    tax_amount       DECIMAL(10,2),
    shipping_cost    DECIMAL(8,2),
    payment_method   NVARCHAR(50),
    shipping_address NVARCHAR(500),
    tracking_number  NVARCHAR(100),
    created_at       DATETIME2 DEFAULT GETDATE(),
    updated_at       DATETIME2 DEFAULT GETDATE(),
    CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES oltp.CUSTOMERS (customer_id)
);

CREATE INDEX idx_order_customer ON oltp.ORDERS (customer_id);
CREATE INDEX idx_order_date     ON oltp.ORDERS (order_date);
CREATE INDEX idx_order_status   ON oltp.ORDERS (status);
GO

-- Order Items Table
CREATE TABLE oltp.ORDER_ITEMS (
    order_item_id BIGINT IDENTITY(1,1) PRIMARY KEY,
    order_id      BIGINT NOT NULL,
    product_id    BIGINT NOT NULL,
    quantity      INT NOT NULL,
    unit_price    DECIMAL(10,2) NOT NULL,
    discount      DECIMAL(8,2) DEFAULT 0,
    subtotal      DECIMAL(12,2),
    CONSTRAINT fk_item_order   FOREIGN KEY (order_id)   REFERENCES oltp.ORDERS   (order_id),
    CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES oltp.PRODUCTS (product_id)
);

CREATE INDEX idx_item_order   ON oltp.ORDER_ITEMS (order_id);
CREATE INDEX idx_item_product ON oltp.ORDER_ITEMS (product_id);
GO

-- Transactions Table
CREATE TABLE oltp.TRANSACTIONS (
    transaction_id         BIGINT IDENTITY(1,1) PRIMARY KEY,
    order_id               BIGINT NOT NULL,
    transaction_type       NVARCHAR(50),
    amount                 DECIMAL(12,2),
    currency               NVARCHAR(10) DEFAULT 'USD',
    payment_gateway        NVARCHAR(100),
    gateway_transaction_id NVARCHAR(200),
    status                 NVARCHAR(50),
    processed_at           DATETIME2 DEFAULT GETDATE(),
    error_message          NVARCHAR(500),
    CONSTRAINT fk_trans_order FOREIGN KEY (order_id) REFERENCES oltp.ORDERS (order_id)
);

CREATE INDEX idx_trans_order  ON oltp.TRANSACTIONS (order_id);
CREATE INDEX idx_trans_status ON oltp.TRANSACTIONS (status);
CREATE INDEX idx_trans_date   ON oltp.TRANSACTIONS (processed_at);
GO

-- Audit Log Table
CREATE TABLE oltp.AUDIT_LOG (
    audit_id   BIGINT IDENTITY(1,1) PRIMARY KEY,
    table_name NVARCHAR(100),
    operation  NVARCHAR(20),
    record_id  BIGINT,
    old_value  NVARCHAR(MAX),
    new_value  NVARCHAR(MAX),
    changed_by NVARCHAR(100),
    changed_at DATETIME2 DEFAULT GETDATE()
);

CREATE INDEX idx_audit_table ON oltp.AUDIT_LOG (table_name);
CREATE INDEX idx_audit_date  ON oltp.AUDIT_LOG (changed_at);
GO

-- Session Data Table
CREATE TABLE oltp.SESSION_DATA (
    session_id    NVARCHAR(100) PRIMARY KEY,
    customer_id   BIGINT,
    login_time    DATETIME2,
    last_activity DATETIME2,
    ip_address    NVARCHAR(50),
    user_agent    NVARCHAR(500),
    session_data  NVARCHAR(MAX),
    is_active     BIT DEFAULT 1
);

CREATE INDEX idx_session_customer ON oltp.SESSION_DATA (customer_id);
CREATE INDEX idx_session_active   ON oltp.SESSION_DATA (is_active);
GO

-- ==================================================
-- STEP 5: Drop and create analytics tables
-- ==================================================

IF OBJECT_ID('analytics.SALES_SUMMARY',       'U') IS NOT NULL DROP TABLE analytics.SALES_SUMMARY;
IF OBJECT_ID('analytics.CUSTOMER_ANALYTICS',  'U') IS NOT NULL DROP TABLE analytics.CUSTOMER_ANALYTICS;
IF OBJECT_ID('analytics.PRODUCT_PERFORMANCE', 'U') IS NOT NULL DROP TABLE analytics.PRODUCT_PERFORMANCE;
GO

-- Aggregated Sales Summary
CREATE TABLE analytics.SALES_SUMMARY (
    summary_id      BIGINT IDENTITY(1,1) PRIMARY KEY,
    summary_date    DATE,
    total_orders    INT,
    total_revenue   DECIMAL(15,2),
    total_customers INT,
    avg_order_value DECIMAL(10,2),
    created_at      DATETIME2 DEFAULT GETDATE()
);

CREATE INDEX idx_sales_date ON analytics.SALES_SUMMARY (summary_date);
GO

-- Customer Analytics
CREATE TABLE analytics.CUSTOMER_ANALYTICS (
    analytics_id     BIGINT IDENTITY(1,1) PRIMARY KEY,
    customer_id      BIGINT,
    total_orders     INT,
    total_spent      DECIMAL(12,2),
    avg_order_value  DECIMAL(10,2),
    last_order_date  DATETIME2,
    customer_segment NVARCHAR(50),
    calculated_at    DATETIME2 DEFAULT GETDATE()
);

CREATE INDEX idx_cust_analytics ON analytics.CUSTOMER_ANALYTICS (customer_id);
GO

-- Product Performance
CREATE TABLE analytics.PRODUCT_PERFORMANCE (
    performance_id BIGINT IDENTITY(1,1) PRIMARY KEY,
    product_id     BIGINT,
    period_start   DATE,
    period_end     DATE,
    units_sold     INT,
    revenue        DECIMAL(12,2),
    profit         DECIMAL(12,2),
    return_count   INT,
    calculated_at  DATETIME2 DEFAULT GETDATE()
);

CREATE INDEX idx_prod_perf ON analytics.PRODUCT_PERFORMANCE (product_id);
GO

-- ==================================================
-- STEP 6: Insert seed data
-- (App also re-seeds on startup; this is a smoke test)
-- ==================================================

SET IDENTITY_INSERT oltp.CUSTOMERS ON;

DECLARE @i INT = 1;
WHILE @i <= 1000
BEGIN
    INSERT INTO oltp.CUSTOMERS
        (customer_id, first_name, last_name, email, phone, city, state, country, customer_type, loyalty_points)
    VALUES (
        @i,
        'FirstName' + CAST(@i AS NVARCHAR),
        'LastName'  + CAST(@i AS NVARCHAR),
        'customer'  + CAST(@i AS NVARCHAR) + '@example.com',
        '555-' + RIGHT('0000000' + CAST(@i AS NVARCHAR), 7),
        'City'  + CAST(@i % 50 AS NVARCHAR),
        'State' + CAST(@i % 50 AS NVARCHAR),
        'USA',
        CASE WHEN @i % 10 = 0 THEN 'PREMIUM' ELSE 'REGULAR' END,
        (@i * 100) % 10000
    );
    SET @i = @i + 1;
END;

SET IDENTITY_INSERT oltp.CUSTOMERS OFF;
GO

SET IDENTITY_INSERT oltp.PRODUCTS ON;

DECLARE @i INT = 1;
WHILE @i <= 500
BEGIN
    INSERT INTO oltp.PRODUCTS
        (product_id, product_name, category, subcategory, price, cost, sku, is_active)
    VALUES (
        @i,
        'Product ' + CAST(@i AS NVARCHAR),
        'Category' + CAST(@i % 10 AS NVARCHAR),
        'SubCat'   + CAST(@i % 20 AS NVARCHAR),
        19.99 + (@i * 1.5),
        10.00 + (@i * 0.75),
        'SKU-' + RIGHT('00000000' + CAST(@i AS NVARCHAR), 8),
        1
    );
    SET @i = @i + 1;
END;

SET IDENTITY_INSERT oltp.PRODUCTS OFF;
GO

SET IDENTITY_INSERT oltp.INVENTORY ON;

DECLARE @i INT = 1;
WHILE @i <= 500
BEGIN
    INSERT INTO oltp.INVENTORY
        (inventory_id, product_id, warehouse_location, quantity_available, reorder_level)
    VALUES (
        @i,
        @i,
        'WH-' + CAST(@i % 5 AS NVARCHAR),
        1000 + (@i * 137) % 5000,
        100
    );
    SET @i = @i + 1;
END;

SET IDENTITY_INSERT oltp.INVENTORY OFF;
GO

-- ==================================================
-- STEP 7: (Monitoring permissions already granted to 'sqlserver' in STEP 2)
-- ==================================================

-- ==================================================
-- STEP 8: Quick verification
-- ==================================================

SELECT
    s.name AS schema_name,
    t.name AS table_name,
    p.rows AS row_count
FROM sys.tables t
JOIN sys.schemas s ON t.schema_id = s.schema_id
JOIN sys.partitions p ON t.object_id = p.object_id AND p.index_id IN (0,1)
WHERE s.name IN ('oltp', 'analytics')
ORDER BY s.name, t.name;
GO
