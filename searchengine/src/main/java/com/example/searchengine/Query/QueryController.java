package com.example.searchengine.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.searchengine.Ranker.Service.RankerService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api")
@CrossOrigin("*") // Enable CORS
public class QueryController {

    private static final Logger logger = LoggerFactory.getLogger(QueryController.class);
    private final QueryService queryService;
    private final RankerService rankerService;
    
    // Query result cache to avoid duplicate processing
    private final Map<String, CacheEntry> queryResultCache = new ConcurrentHashMap<>();
    
    // Search session cache for consistent pagination
    private final Map<String, SearchSession> searchSessionCache = new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    private static final long CACHE_EXPIRY_MINUTES = 5;
    private static final long SESSION_EXPIRY_MINUTES = 30;
    private static final int MAX_CACHE_SIZE = 100;
    private static final int MAX_SESSION_SIZE = 100;
    
    @Autowired
    public QueryController(QueryService queryService, RankerService rankerService) {
        this.queryService = queryService;
        this.rankerService = rankerService;
    }
    
    @PostConstruct
    public void init() {
        // Schedule regular cache cleanup
        cleanupExecutor.scheduleAtFixedRate(this::cleanupCache, 1, 1, TimeUnit.MINUTES);
    }
    
    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
    }
    
    /**
     * Custom cache entry with timestamp
     */
    private static class CacheEntry {
        final QueryResult result;
        final long timestamp;
        
        CacheEntry(QueryResult result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TimeUnit.MINUTES.toMillis(CACHE_EXPIRY_MINUTES);
        }
    }
    
    /**
     * Search session for consistent pagination
     */
    private static class SearchSession {
        final String query;
        final List<Map<String, Object>> results;
        final long timestamp;
        
        SearchSession(String query, List<Map<String, Object>> results) {
            this.query = query;
            this.results = results;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TimeUnit.MINUTES.toMillis(SESSION_EXPIRY_MINUTES);
        }
    }
    
    /**
     * Clean up expired cache entries
     */
    private void cleanupCache() {
        try {
            // Clean query cache
            int beforeQuerySize = queryResultCache.size();
            queryResultCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            int afterQuerySize = queryResultCache.size();
            
            // Clean session cache
            int beforeSessionSize = searchSessionCache.size();
            searchSessionCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            int afterSessionSize = searchSessionCache.size();
            
            if (beforeQuerySize > afterQuerySize || beforeSessionSize > afterSessionSize) {
                logger.info("Cache cleanup: removed {} query entries, {} session entries", 
                        beforeQuerySize - afterQuerySize, beforeSessionSize - afterSessionSize);
            }
        } catch (Exception e) {
            logger.error("Error during cache cleanup", e);
        }
    }
    
    /**
     * Ensure the cache doesn't exceed maximum size
     */
    private void ensureCacheSize() {
        // Ensure query cache size
        if (queryResultCache.size() > MAX_CACHE_SIZE) {
            while (queryResultCache.size() > MAX_CACHE_SIZE) {
                String keyToRemove = queryResultCache.keySet().iterator().next();
                queryResultCache.remove(keyToRemove);
            }
        }
        
        // Ensure session cache size
        if (searchSessionCache.size() > MAX_SESSION_SIZE) {
            while (searchSessionCache.size() > MAX_SESSION_SIZE) {
                String keyToRemove = searchSessionCache.keySet().iterator().next();
                searchSessionCache.remove(keyToRemove);
            }
        }
    }

    /**
     * Gets a query result either from cache or by processing it
     */
    private QueryResult getQueryResult(String query) {
        // Check in cache
        CacheEntry entry = queryResultCache.get(query);
        
        if (entry != null && !entry.isExpired()) {
            // Valid cache entry
            logger.info("Using cached query result for: {}", query);
            return entry.result;
        }
        
        // Not in cache or expired, process it
        QueryResult result = queryService.processQuery(query);
        
        // Store in cache
        queryResultCache.put(query, new CacheEntry(result));
        ensureCacheSize();
        logger.info("Processed and cached query: {}", query);
        
        return result;
    }

    @GetMapping("/query-analysis")
    public ResponseEntity<Map<String, Object>> processQuery(@RequestParam String query) {
        QueryResult result = getQueryResult(query);
        
        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", result.getOriginalQuery());
        response.put("isPhraseQuery", result.isPhraseQuery());
        response.put("phrases", result.getPhrases());
        response.put("stemmedWords", result.getStemmedWords());
        response.put("operator", result.getOperator());
        
        // No need to send all document IDs back to client
        // response.put("matchingDocuments", result.getMatchingDocuments());
        
        return ResponseEntity.ok(response);
    }
    
    
    /**
     * Execute a query and get the result
     */

    

    
    @GetMapping("/voice-search")
    public ResponseEntity<Map<String, Object>> voiceSearch(@RequestParam String query) {
        // For now, just delegate to the standard search with special tracking for voice
        logger.info("Voice search: {}", query);
        
        return processQuery(query);
    }
} 