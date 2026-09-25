package com.loadgen.oltp.controller;

import com.loadgen.oltp.SessionService;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for Session operations
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/create")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> createSession(@RequestParam long customerId) {
        try {
            NewRelic.addCustomParameter("customerId", customerId);

            String sessionId = sessionService.createSession(customerId);
            sessionService.updateSessionActivity(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", sessionId);
            response.put("customerId", customerId);
            response.put("status", "ACTIVE");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error creating session", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @DeleteMapping("/expire")
    @Trace(dispatcher = true)
    public ResponseEntity<Map<String, Object>> expireSessions() {
        try {
            sessionService.expireOldSessions();
            sessionService.deleteExpiredSessions();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "EXPIRED");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error expiring sessions", e);
            NewRelic.noticeError(e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
