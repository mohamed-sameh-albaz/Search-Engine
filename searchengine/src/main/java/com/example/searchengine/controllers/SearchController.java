package com.example.searchengine.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.searchengine.Query.QueryResult;
import com.example.searchengine.Query.QueryService;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SearchController {

    private final QueryService queryService;
    private static final Logger logger = LoggerFactory.getLogger(SearchController.class);
    
    // Search result cache to avoid repeated query processing
    private final Map<String, CacheEntry> searchResultCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cacheCleanupService = Executors.newScheduledThreadPool(1);
    private static final int CACHE_MAX_SIZE = 500;
    private static final long CACHE_EXPIRY_MINUTES = 30; // 30 minutes cache expiry
    
    @Autowired
    public SearchController(QueryService queryService) {
        this.queryService = queryService;
    }
    
    @PostConstruct
    public void init() {
        // Schedule cache cleanup every 10 minutes
        cacheCleanupService.scheduleAtFixedRate(this::cleanupCache, 10, 10, TimeUnit.MINUTES);
    }
    
    @PreDestroy
    public void destroy() {
        cacheCleanupService.shutdown();
    }
    
    /**
     * Clean up expired entries from the cache
     */
    private void cleanupCache() {
        try {
            int beforeSize = searchResultCache.size();
            searchResultCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            int afterSize = searchResultCache.size();
            if (beforeSize > afterSize) {
                logger.info("Cache cleanup: removed {} expired entries", beforeSize - afterSize);
            }
        } catch (Exception e) {
            logger.error("Error during cache cleanup", e);
        }
    }
    
    /**
     * Inner class to hold cached search results with metadata
     */
    private static class CacheEntry {
        private final QueryResult queryResult;
        private final List<Map<String, Object>> formattedResults;
        private final Map<Integer, List<Map<String, Object>>> pageCache = new HashMap<>();
        private final long timestamp;
        private final int totalResults;
        
        public CacheEntry(QueryResult queryResult, List<Map<String, Object>> formattedResults, int totalResults) {
            this.queryResult = queryResult;
            this.formattedResults = formattedResults;
            this.totalResults = totalResults;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TimeUnit.MINUTES.toMillis(CACHE_EXPIRY_MINUTES);
        }
        
        public void addPageResults(int page, int pageSize, List<Map<String, Object>> results) {
            pageCache.put(page, results);
        }
        
        public List<Map<String, Object>> getPageResults(int page, int pageSize) {
            return pageCache.getOrDefault(page, null);
        }
    }
    
    /**
     * Get cached results or process a new query
     */
    private CacheEntry getOrCreateCacheEntry(String query) {
        CacheEntry entry = searchResultCache.get(query);
        
        if (entry != null && !entry.isExpired()) {
            logger.debug("Cache hit for query: {}", query);
            return entry;
        }
        
        // Process the query only once if not in cache or expired
        long startProcessing = System.currentTimeMillis();
        
        // Process query to get the results
        QueryResult queryResult = queryService.processQuery(query);
        List<Map<String, Object>> allResults = queryResult.getResults();
        int totalResults = allResults != null ? allResults.size() : 0;
        
        // Format all results at once
        List<Map<String, Object>> formattedResults = queryService.formatSearchResults(allResults);
        
        // Create and store the cache entry
        entry = new CacheEntry(queryResult, formattedResults, totalResults);
        searchResultCache.put(query, entry);
        
        long processingTime = System.currentTimeMillis() - startProcessing;
        logger.info("Query processed and cached in {} ms: {}", processingTime, query);
        
        // Ensure cache doesn't grow too large
        if (searchResultCache.size() > CACHE_MAX_SIZE) {
            // Simplest approach: remove a random entry
            // A better approach would use LRU (least recently used) strategy
            String keyToRemove = searchResultCache.keySet().iterator().next();
            searchResultCache.remove(keyToRemove);
            logger.debug("Cache reached max size, removed entry: {}", keyToRemove);
        }
        
        return entry;
    }

    /**
     * Format a search result entry for the frontend
     * @param result The raw search result from the query service
     * @return A cleaned up version for the frontend
     */
    private Map<String, Object> formatSearchResult(Map<String, Object> result) {
        Map<String, Object> formatted = new HashMap<>();
        
        // Copy basic properties
        formatted.put("title", result.get("title"));
        formatted.put("url", result.get("url"));
        
        // Format the description - prioritize the generated description
        String description = (String) result.get("description");
        if (description == null || description.isEmpty()) {
            // Fallback to snippet if description is missing
            description = (String) result.get("snippet");
        }
        
        // Clean up any strange formatting in the description
        if (description != null) {
            // Remove any base64 or data URIs which can be long and useless
            description = description.replaceAll("data:[^\\s]*;base64,[^\\s]*", "");
            
            // Remove any JS variable declarations
            description = description.replaceAll("var\\s+[^=]+=\\s*[^;]*;", "");
            
            // Remove any CSS style blocks
            description = description.replaceAll("\\{--[^}]*\\}", "");
            
            // Truncate if too long
            if (description.length() > 200) {
                description = description.substring(0, 200) + "...";
            }
            
            // Clean up whitespace
            description = description.replaceAll("\\s+", " ").trim();
        }
        
        // Add both description and snippet fields to ensure compatibility with frontend
        formatted.put("description", description);
        formatted.put("snippet", description);
        
        // Include the score
        formatted.put("score", result.get("score"));
        
        return formatted;
    }
    
    /**
     * Primary search endpoint - optimized to use caching
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String query, 
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int pageSize,
                                                      @RequestParam(required = false) String sessionId) {
        try {
            long startTime = System.currentTimeMillis();
            
            // Prepare response
            Map<String, Object> response = new HashMap<>();
            
            // Check if this is a phrase query (enclosed in quotes)
            boolean isPhraseQuery = query.startsWith("\"") && query.endsWith("\"");
            
            // Create session ID if not provided
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = UUID.randomUUID().toString();
            }
            
            // Get or create cache entry for this query (processes query only if needed)
            CacheEntry cacheEntry = getOrCreateCacheEntry(query);
            
            // Get total results count (already calculated and stored in cache)
            int totalResults = isPhraseQuery ? 
                Math.min(cacheEntry.totalResults, 100) : // Limit phrase queries to reporting max 100 results
                cacheEntry.totalResults;
                
            int totalPages = (int) Math.ceil((double) totalResults / pageSize);
            
            // See if we have the requested page in cache
            List<Map<String, Object>> pageResults = cacheEntry.getPageResults(page, pageSize);
            
            // If no cached page results, calculate them from the full results
            if (pageResults == null) {
                // Calculate page boundaries
                int fromIndex = (page - 1) * pageSize;
                int toIndex = Math.min(fromIndex + pageSize, cacheEntry.formattedResults.size());
                
                // Check if the page is valid
                if (fromIndex >= cacheEntry.formattedResults.size()) {
                    pageResults = new ArrayList<>();
                } else {
                    pageResults = cacheEntry.formattedResults.subList(fromIndex, toIndex);
                }
                
                // Cache this page's results
                cacheEntry.addPageResults(page, pageSize, pageResults);
            }
            
            // Calculate stats
            long endTime = System.currentTimeMillis();
            double searchTimeMs = (endTime - startTime);
            
            // Create response
            response.put("results", pageResults);
            response.put("page", page);
            response.put("pageSize", pageSize);
            response.put("totalResults", totalResults);
            response.put("totalPages", totalPages);
            response.put("searchTime", searchTimeMs);
            response.put("query", query);
            response.put("sessionId", sessionId);
            response.put("isPhraseQuery", isPhraseQuery);
            response.put("fromCache", true);
            
            // Log successful search
            logger.info("Search for '{}' returned {} results in {:.2f} ms (page {}/{})", 
                      query, totalResults, searchTimeMs, page, totalPages);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error processing search for query '{}': {}", query, e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Search failed: " + e.getMessage());
            errorResponse.put("query", query);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Process a query and return analysis information (used by the UI for query suggestions)
     */
    @GetMapping("/process-query")
    public ResponseEntity<Map<String, Object>> processQuery(@RequestParam String query) {
        try {
            // Get the cached entry (or create one if needed)
            CacheEntry entry = getOrCreateCacheEntry(query);
            
            // Extract query information
            Map<String, Object> response = new HashMap<>();
            response.put("originalQuery", entry.queryResult.getOriginalQuery());
            response.put("isPhraseQuery", entry.queryResult.isPhraseQuery());
            response.put("phrases", entry.queryResult.getPhrases());
            response.put("stemmedWords", entry.queryResult.getStemmedWords());
            response.put("operator", entry.queryResult.getOperator());
            response.put("totalResults", entry.totalResults);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error processing query information: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Query processing failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
} 