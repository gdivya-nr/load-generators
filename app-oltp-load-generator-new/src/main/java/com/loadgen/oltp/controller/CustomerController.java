package com.loadgen.oltp.controller;

import com.loadgen.oltp.CustomerService;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for Customer operations
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private static final Logger logger = LoggerFactory.getLogger(CustomerController.class);

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PutMapping("/{customerId}/loyalty")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> updateLoyaltyPoints(@PathVariable long customerId,
                                                                   @RequestParam int points) {
        try {
            NewRelic.addCustomParameter("customerId", customerId);
            NewRelic.addCustomParameter("points", points);

            customerService.updateLoyaltyPoints(customerId, points);

            Map<String, Object> response = new HashMap<>();
            response.put("customerId", customerId);
            response.put("pointsAdded", points);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error updating loyalty points", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PutMapping("/{customerId}/upgrade")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> upgradeCustomerType(@PathVariable long customerId) {
        try {
            NewRelic.addCustomParameter("customerId", customerId);

            customerService.upgradeCustomerType(customerId);

            Map<String, Object> response = new HashMap<>();
            response.put("customerId", customerId);
            response.put("upgraded", true);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error upgrading customer", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/{customerId}/access-log")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> logCustomerAccess(@PathVariable long customerId) {
        try {
            NewRelic.addCustomParameter("customerId", customerId);

            customerService.logCustomerAccess(customerId);

            Map<String, Object> response = new HashMap<>();
            response.put("customerId", customerId);
            response.put("logged", true);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error logging customer access", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
