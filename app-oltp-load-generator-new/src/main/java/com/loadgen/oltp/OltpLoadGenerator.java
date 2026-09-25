package com.loadgen.oltp;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * OLTP Load Generator - Simulates high-frequency transactional operations via REST APIs
 * This application generates massive load by making HTTP calls to REST endpoints:
 * - Customer creation and updates
 * - Order processing
 * - Inventory management
 * - Transaction logging
 * - Session tracking
 */
@Component
public class OltpLoadGenerator {
    private static final Logger logger = LoggerFactory.getLogger(OltpLoadGenerator.class);
    private static final Random random = new Random();

    private final RestTemplate restTemplate;
    private final DatabaseManager dbManager;
    private final int numThreads;
    private final String apiBaseUrl;
    private final ExecutorService executorService;
    private volatile boolean running = true;

    public OltpLoadGenerator(RestTemplate restTemplate,
                            DatabaseManager dbManager,
                            @Value("${threads:3}") int numThreads,
                            @Value("${api.base.url:http://localhost:8080}") String apiBaseUrl) {
        this.restTemplate = restTemplate;
        this.dbManager = dbManager;
        this.numThreads = numThreads;
        this.apiBaseUrl = apiBaseUrl;
        this.executorService = Executors.newFixedThreadPool(numThreads);

        logger.info("OLTP Load Generator initialized with {} threads", numThreads);
        logger.info("API Base URL: {}", apiBaseUrl);
        NewRelic.setTransactionName("OltpLoadGenerator", "Initialize");
    }

    public void start() {
        logger.info("Starting OLTP load generation...");

        List<Future<?>> futures = new ArrayList<>();

        // Start multiple worker threads
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            Future<?> future = executorService.submit(() -> runWorker(threadId));
            futures.add(future);
        }

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping load generator...");
            shutdown();
        }));

        // Keep main thread alive
        try {
            while (running) {
                Thread.sleep(10000);
                logStatistics();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Main thread interrupted", e);
        }
    }

    private void runWorker(int threadId) {
        logger.info("Worker thread {} started", threadId);

        int operationCount = 0;
        long lastBreakTime = System.currentTimeMillis();
        int cycleOperations = 0;
        int consecutiveErrors = 0;

        while (running) {
            try {
                // Check if it's time for a break (sustainable load)
                long currentTime = System.currentTimeMillis();
                long timeSinceBreak = currentTime - lastBreakTime;

                // Take a break every 2-3 minutes (randomized per thread)
                int breakInterval = 120000 + random.nextInt(60000); // 120-180 seconds (2-3 minutes)
                if (timeSinceBreak > breakInterval) {
                    logger.info("Worker thread {} taking 5-second break after {} operations", threadId, cycleOperations);
                    Thread.sleep(5000); // 5 second break
                    lastBreakTime = System.currentTimeMillis();
                    cycleOperations = 0;
                    logger.info("Worker thread {} resuming work", threadId);
                }

                // Randomly select operation type with REDUCED weighted distribution
                int operation = random.nextInt(100);

                if (operation < 30) {
                    // 30% - Create new orders (reduced frequency)
                    createOrderWorkflow();
                } else if (operation < 55) {
                    // 25% - Update customer information (SIMPLIFIED)
                    updateCustomerWorkflow();
                } else if (operation < 75) {
                    // 20% - Check and update inventory
                    inventoryCheckWorkflow();
                } else if (operation < 90) {
                    // 15% - Process transactions
                    processTransactionWorkflow();
                } else if (operation < 95) {
                    // 5% - Session management
                    sessionManagementWorkflow();
                } else if (operation < 98) {
                    // 3% - Delete old data (HEAVILY REDUCED)
                    deleteOldDataWorkflow();
                } else if (operation < 99) {
                    // 1% - Bulk insert operations (HEAVILY REDUCED)
                    bulkInsertWorkflow();
                } else {
                    // 1% - Product operations (HEAVILY REDUCED)
                    productOperationsWorkflow();
                }

                operationCount++;
                cycleOperations++;
                consecutiveErrors = 0; // Reset error counter on success

                // MODERATE-HEAVY LOAD - gradual increase for testing
                Thread.sleep(random.nextInt(50) + 30); // 30-80ms delay = MODERATE-HEAVY LOAD

            } catch (Exception e) {
                consecutiveErrors++;
                logger.error("Error in worker thread {} (consecutive errors: {}): {}", threadId, consecutiveErrors, e.getMessage());
                NewRelic.noticeError(e);

                // Exponential backoff: 1s, 2s, 4s, 8s (max 8s)
                int backoffMs = Math.min(1000 * (1 << (consecutiveErrors - 1)), 8000);
                logger.warn("Worker thread {} backing off for {}ms after {} consecutive errors", threadId, backoffMs, consecutiveErrors);

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        logger.info("Worker thread {} completed {} operations", threadId, operationCount);
    }

    @Trace(dispatcher = true)
    private void createOrderWorkflow() {
        try {
            // Get random customer
            long customerId = random.nextInt(1000) + 1;
            int numItems = random.nextInt(5) + 1;

            // Call REST API to create order
            String url = apiBaseUrl + "/api/orders/create?customerId=" + customerId + "&numItems=" + numItems;
            restTemplate.postForObject(url, null, String.class);

        } catch (Exception e) {
            logger.error("Error in createOrderWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    @Trace(dispatcher = true)
    private void updateCustomerWorkflow() {
        try {
            long customerId = random.nextInt(1000) + 1;

            // SIMPLIFIED: Only update customer loyalty points via REST API
            int points = random.nextInt(100);
            String url = apiBaseUrl + "/api/customers/" + customerId + "/loyalty?points=" + points;
            restTemplate.put(url, null);

            // REMOVED: Upgrade and access log calls to reduce load

        } catch (Exception e) {
            logger.error("Error in updateCustomerWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    @Trace(dispatcher = true)
    private void inventoryCheckWorkflow() {
        try {
            long productId = random.nextInt(500) + 1;

            // Check inventory via REST API (also auto-restocks if low)
            String url = apiBaseUrl + "/api/inventory/" + productId + "/check";
            restTemplate.getForObject(url, String.class);

        } catch (Exception e) {
            logger.error("Error in inventoryCheckWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    @Trace(dispatcher = true)
    private void processTransactionWorkflow() {
        try {
            // Get random recent order ID
            long orderId = random.nextInt(1000) + 1;

            // Process payment via REST API
            String url = apiBaseUrl + "/api/transactions/process?orderId=" + orderId;
            restTemplate.postForObject(url, null, String.class);

        } catch (Exception e) {
            logger.error("Error in processTransactionWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    @Trace(dispatcher = true)
    private void sessionManagementWorkflow() {
        try {
            long customerId = random.nextInt(1000) + 1;

            // SIMPLIFIED: Only create session via REST API
            String url = apiBaseUrl + "/api/sessions/create?customerId=" + customerId;
            restTemplate.postForObject(url, null, String.class);

            // REMOVED: Session expiry to reduce load

        } catch (Exception e) {
            logger.error("Error in sessionManagementWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    @Trace(dispatcher = true)
    private void productOperationsWorkflow() {
        try {
            long productId = random.nextInt(500) + 1;

            // SIMPLIFIED: Only query product details (read-only operation)
            String getUrl = apiBaseUrl + "/api/products/" + productId;
            restTemplate.getForObject(getUrl, String.class);

            // REMOVED: Price update and search to reduce load

        } catch (Exception e) {
            logger.error("Error in productOperationsWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    @Trace(dispatcher = true)
    private void deleteOldDataWorkflow() {
        try {
            // Delete old orders via REST API
            String url = apiBaseUrl + "/api/orders/old?daysToKeep=30";
            restTemplate.delete(url);

        } catch (Exception e) {
            logger.error("Error in deleteOldDataWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    @Trace(dispatcher = true)
    private void bulkInsertWorkflow() {
        try {
            // REDUCED: Bulk create fewer orders at once via REST API
            int batchSize = random.nextInt(2) + 2; // 2-3 orders at once (reduced from 3-7)
            String url = apiBaseUrl + "/api/orders/bulk?batchSize=" + batchSize;
            restTemplate.postForObject(url, null, String.class);

        } catch (Exception e) {
            logger.error("Error in bulkInsertWorkflow", e);
            NewRelic.noticeError(e);
        }
    }

    private void logStatistics() {
        try {
            int activeConnections = dbManager.getActiveConnections();
            logger.info("Active DB connections: {}", activeConnections);
            NewRelic.recordMetric("Custom/Database/ActiveConnections", activeConnections);
        } catch (Exception e) {
            logger.error("Error logging statistics", e);
        }
    }

    public void shutdown() {
        running = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("OLTP Load Generator shutdown complete");
    }
}
