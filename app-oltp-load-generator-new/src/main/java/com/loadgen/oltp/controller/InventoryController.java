package com.loadgen.oltp.controller;

import com.loadgen.oltp.InventoryService;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for Inventory operations
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{productId}/check")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> checkInventory(@PathVariable long productId) {
        try {
            NewRelic.addCustomParameter("productId", productId);

            int available = inventoryService.checkAvailability(productId);

            // Auto-restock if low
            if (available < 100) {
                int restockAmount = (int) (Math.random() * 500) + 100;
                inventoryService.restockInventory(productId, restockAmount);
                available += restockAmount;
            }

            inventoryService.updateWarehouseLocation(productId);

            Map<String, Object> response = new HashMap<>();
            response.put("productId", productId);
            response.put("available", available);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error checking inventory", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PutMapping("/{productId}/restock")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> restockInventory(@PathVariable long productId,
                                                                @RequestParam int quantity) {
        try {
            NewRelic.addCustomParameter("productId", productId);
            NewRelic.addCustomParameter("quantity", quantity);

            inventoryService.restockInventory(productId, quantity);

            Map<String, Object> response = new HashMap<>();
            response.put("productId", productId);
            response.put("restocked", quantity);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error restocking inventory", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PutMapping("/bulk-update")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> bulkUpdateInventory() {
        try {
            inventoryService.bulkUpdateInventory();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("updated", true);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error bulk updating inventory", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
