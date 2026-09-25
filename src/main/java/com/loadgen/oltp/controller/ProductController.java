package com.loadgen.oltp.controller;

import com.loadgen.oltp.ProductService;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for Product operations
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {
    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PutMapping("/{productId}/price")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> updatePrice(@PathVariable long productId) {
        try {
            NewRelic.addCustomParameter("productId", productId);

            productService.updatePrice(productId);

            Map<String, Object> response = new HashMap<>();
            response.put("productId", productId);
            response.put("updated", true);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error updating product price", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/{productId}")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> getProductDetails(@PathVariable long productId) {
        try {
            NewRelic.addCustomParameter("productId", productId);

            productService.getProductDetails(productId);

            Map<String, Object> response = new HashMap<>();
            response.put("productId", productId);
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting product details", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/search")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> searchByCategory() {
        try {
            productService.searchByCategory();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error searching products", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
