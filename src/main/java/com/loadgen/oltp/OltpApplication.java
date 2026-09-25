package com.loadgen.oltp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Boot Application for OLTP Load Generator
 * Exposes REST APIs and runs load generation worker threads
 */
@SpringBootApplication
@EnableScheduling
public class OltpApplication {
    private static final Logger logger = LoggerFactory.getLogger(OltpApplication.class);

    @Autowired
    @Lazy
    private OltpLoadGenerator loadGenerator;

    @Autowired
    private TableCleanupService tableCleanupService;

    public static void main(String[] args) {
        logger.info("=" .repeat(80));
        logger.info("OLTP Load Generator Starting");
        logger.info("=" .repeat(80));
        SpringApplication.run(OltpApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startLoadGeneration() {
        logger.info("Application ready, performing initial cleanup and rebuild...");

        // Initial cleanup and rebuild on startup
        try {
            tableCleanupService.truncateAndRebuild();
        } catch (Exception e) {
            logger.error("Failed to clean up and rebuild tables, aborting startup", e);
            System.exit(1);
        }

        // Start load generator in a separate thread so it doesn't block Spring Boot
        new Thread(() -> loadGenerator.start(), "LoadGeneratorMain").start();
    }

    /**
     * Scheduled cleanup: Truncate and rebuild tables every 35 minutes
     * This allows gradual data accumulation for slow traces
     * while preventing receiver from being overwhelmed
     */
    @Scheduled(fixedDelay = 2100000, initialDelay = 2100000) // 2100000ms = 35 minutes
    public void scheduledCleanup() {
        logger.info("Running scheduled cleanup (every 35 minutes)...");
        try {
            tableCleanupService.truncateAndRebuild();
            logger.info("Scheduled cleanup completed successfully");
        } catch (Exception e) {
            logger.error("Error during scheduled cleanup", e);
            // Don't exit - just log and continue
        }
    }
}
