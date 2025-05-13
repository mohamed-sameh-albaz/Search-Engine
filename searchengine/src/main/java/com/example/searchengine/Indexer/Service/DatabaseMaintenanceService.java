package com.example.searchengine.Indexer.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for database maintenance operations
 */
@Service
public class DatabaseMaintenanceService {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseMaintenanceService.class);
    
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Run VACUUM on various tables to optimize database performance
     * 
     * IMPORTANT: VACUUM cannot run inside a transaction in PostgreSQL,
     * so we use Propagation.NOT_SUPPORTED to ensure this runs outside
     * any existing transaction
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void vacuumDatabase() {
        logger.info("Running database maintenance (VACUUM)...");
        try {
            // Execute VACUUM on important tables
            jdbcTemplate.execute("VACUUM ANALYZE word_document_tags");
            jdbcTemplate.execute("VACUUM ANALYZE inverted_index");
            jdbcTemplate.execute("VACUUM ANALYZE words");
            jdbcTemplate.execute("VACUUM ANALYZE documents");
            logger.info("Database maintenance completed successfully");
        } catch (Exception e) {
            logger.error("Error performing database maintenance: {}", e.getMessage());
            // Don't rethrow - we don't want application startup to fail if vacuum fails
        }
    }
}