package com.loadgen.oltp.controller;

import com.loadgen.oltp.*;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for Order operations
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final TransactionService transactionService;
    private final InventoryService inventoryService;

    public OrderController(OrderService orderService,
                          TransactionService transactionService,
                          InventoryService inventoryService) {
        this.orderService = orderService;
        this.transactionService = transactionService;
        this.inventoryService = inventoryService;
    }

    @PostMapping("/create")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> createOrder(@RequestParam long customerId,
                                                           @RequestParam int numItems) {
        try {
            NewRelic.addCustomParameter("customerId", customerId);
            NewRelic.addCustomParameter("numItems", numItems);

            // Create order
            long orderId = orderService.createOrder(customerId);

            // Add items
            for (int i = 0; i < numItems; i++) {
                long productId = (long) (Math.random() * 500) + 1;
                int quantity = (int) (Math.random() * 5) + 1;
                orderService.addOrderItem(orderId, productId, quantity);
            }

            // Calculate total
            orderService.calculateOrderTotal(orderId);

            // Create transaction
            transactionService.createTransaction(orderId, "PAYMENT");

            // Reserve inventory
            inventoryService.reserveInventory(orderId);

            // Log audit
            orderService.logAudit("ORDERS", orderId, "CREATE");

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", orderId);
            response.put("customerId", customerId);
            response.put("itemsAdded", numItems);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error creating order", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("status", "ERROR");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PutMapping("/{orderId}/status")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> updateOrderStatus(@PathVariable long orderId,
                                                                 @RequestParam String status) {
        try {
            NewRelic.addCustomParameter("orderId", orderId);
            NewRelic.addCustomParameter("status", status);

            orderService.updateOrderStatus(orderId, status);

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", orderId);
            response.put("status", status);
            response.put("updated", true);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error updating order status", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @DeleteMapping("/old")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> deleteOldOrders(@RequestParam(defaultValue = "30") int daysToKeep) {
        try {
            NewRelic.addCustomParameter("daysToKeep", daysToKeep);

            orderService.deleteOldCompletedOrders(daysToKeep);
            orderService.deleteCancelledOrders();
            orderService.deleteOldAuditLogs(7);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "DELETED");
            response.put("daysKept", daysToKeep);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error deleting old orders", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/bulk")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> bulkCreateOrders(@RequestParam int batchSize) {
        try {
            NewRelic.addCustomParameter("batchSize", batchSize);

            int created = 0;
            for (int i = 0; i < batchSize; i++) {
                long customerId = (long) (Math.random() * 1000) + 1;
                long orderId = orderService.createOrder(customerId);

                int numItems = (int) (Math.random() * 3) + 1;
                for (int j = 0; j < numItems; j++) {
                    long productId = (long) (Math.random() * 500) + 1;
                    int quantity = (int) (Math.random() * 3) + 1;
                    orderService.addOrderItem(orderId, productId, quantity);
                }

                orderService.calculateOrderTotal(orderId);
                created++;
            }

            inventoryService.bulkUpdateInventory();

            Map<String, Object> response = new HashMap<>();
            response.put("created", created);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error bulk creating orders", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
