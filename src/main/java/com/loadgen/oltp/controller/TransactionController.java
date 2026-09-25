package com.loadgen.oltp.controller;

import com.loadgen.oltp.OrderService;
import com.loadgen.oltp.TransactionService;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for Transaction operations
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;
    private final OrderService orderService;

    public TransactionController(TransactionService transactionService, OrderService orderService) {
        this.transactionService = transactionService;
        this.orderService = orderService;
    }

    @PostMapping("/process")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> processPayment(@RequestParam long orderId) {
        try {
            NewRelic.addCustomParameter("orderId", orderId);

            boolean success = transactionService.processPayment(orderId);

            if (success) {
                orderService.updateOrderStatus(orderId, "COMPLETED");
            } else {
                orderService.updateOrderStatus(orderId, "PAYMENT_FAILED");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", orderId);
            response.put("success", success);
            response.put("status", success ? "COMPLETED" : "PAYMENT_FAILED");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error processing payment", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
