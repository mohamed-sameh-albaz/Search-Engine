package com.example.searchengine.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
        // Normalize query for consistent caching
        String normalizedQuery = query.replaceAll("(?i)\\band\\b", "AND")
        .replaceAll("(?i)\\bor\\b", "OR")
        .replaceAll("(?i)\\bnot\\b", "NOT");        CacheEntry entry = searchResultCache.get(normalizedQuery);

        if (entry != null && !entry.isExpired()) {
            logger.debug("Cache hit for query: {}", normalizedQuery);
            return entry;
        }

        // Process the query only once if not in cache or expired
        long startProcessing = System.currentTimeMillis();

        // Process query to get the results
        QueryResult queryResult = queryService.processQuery(normalizedQuery);
        List<Map<String, Object>> allResults = queryResult.getResults();
        int totalResults = allResults != null ? allResults.size() : 0;

        // Format all results at once
        List<Map<String, Object>> formattedResults = new ArrayList<>();
        for (Map<String, Object> result : allResults) {
            formattedResults.add(formatSearchResult(result));
        }

        // Create and store the cache entry
        entry = new CacheEntry(queryResult, formattedResults, totalResults);
        searchResultCache.put(normalizedQuery, entry);

        long processingTime = System.currentTimeMillis() - startProcessing;
        logger.info("Query processed and cached in {} ms: {}", processingTime, normalizedQuery);

        // Ensure cache doesn't grow too large
        if (searchResultCache.size() > CACHE_MAX_SIZE) {
            String keyToRemove = searchResultCache.keySet().iterator().next();
            searchResultCache.remove(keyToRemove);
            logger.debug("Cache reached max size, removed entry: {}", keyToRemove);
        }

        return entry;
    }

    /**
     * Format a search result entry for the frontend
     */
    private Map<String, Object> formatSearchResult(Map<String, Object> result) {
        Map<String, Object> formatted = new HashMap<>();

        // Copy basic properties
        formatted.put("title", result.get("title"));
        formatted.put("url", result.get("url"));

        // Format the description - prioritize the generated description
        String description = (String) result.get("description");
        if (description == null || description.isEmpty()) {
            description = (String) result.get("snippet");
        }

        // Clean up description
        if (description != null) {
            description = description.replaceAll("data:[^\\s]*;base64,[^\\s]*", "");
            description = description.replaceAll("var\\s+[^=]+=\\s*[^;]*;", "");
            description = description.replaceAll("\\{--[^}]*\\}", "");
            if (description.length() > 200) {
                description = description.substring(0, 200) + "...";
            }
            description = description.replaceAll("\\s+", " ").trim();
        }

        formatted.put("description", description);
        formatted.put("snippet", description);
        formatted.put("score", result.get("score"));

        return formatted;
    }

    /**
     * Primary search endpoint - optimized to use caching
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String query,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "25") int pageSize,
                                                     @RequestParam(required = false) String sessionId) {
        try {
            long startTime = System.currentTimeMillis();

            // Normalize query for consistent processing and caching
            String normalizedQuery = query.replaceAll("(?i)\\band\\b", "AND")
            .replaceAll("(?i)\\bor\\b", "OR")
            .replaceAll("(?i)\\bnot\\b", "NOT");
            // Prepare response
            Map<String, Object> response = new HashMap<>();

            // Check if this is a phrase query (contains quotes)
            boolean isPhraseQuery = normalizedQuery.contains("\"");

            // Check if this is a complex phrase query with operators
            boolean isComplexPhraseQuery = false;
            String operator = null;
            if (normalizedQuery.contains(" AND ") || normalizedQuery.contains(" OR ") || normalizedQuery.contains(" NOT ")) {
                isComplexPhraseQuery = true;
                if (normalizedQuery.contains(" AND ")) {
                    operator = "AND";
                } else if (normalizedQuery.contains(" OR ")) {
                    operator = "OR";
                } else if (normalizedQuery.contains(" NOT ")) {
                    operator = "NOT";
                }
            }

            // Create session ID if not provided
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = UUID.randomUUID().toString();
            }

            // Get or create cache entry for this query
            CacheEntry cacheEntry = getOrCreateCacheEntry(normalizedQuery);

            // Get total results count
            int totalResults = isPhraseQuery || isComplexPhraseQuery ?
                    Math.min(cacheEntry.totalResults, 100) :
                    cacheEntry.totalResults;

            int totalPages = (int) Math.ceil((double) totalResults / pageSize);

            // Get page results from cache or calculate
            List<Map<String, Object>> pageResults = cacheEntry.getPageResults(page, pageSize);
            if (pageResults == null) {
                int fromIndex = (page - 1) * pageSize;
                int toIndex = Math.min(fromIndex + pageSize, cacheEntry.formattedResults.size());

                if (fromIndex >= cacheEntry.formattedResults.size()) {
                    pageResults = new ArrayList<>();
                } else {
                    pageResults = cacheEntry.formattedResults.subList(fromIndex, toIndex);
                }

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
            response.put("query", normalizedQuery);
            response.put("displayQuery", query); // Preserve original query for display
            response.put("sessionId", sessionId);
            response.put("isPhraseQuery", isPhraseQuery);
            response.put("isComplexPhraseQuery", isComplexPhraseQuery);
            response.put("operator", operator);
            response.put("operatorDisplayText", operator != null ? operator : "");
            response.put("fromCache", cacheEntry.getPageResults(page, pageSize) != null);

            // Log successful search
            logger.info("Search for '{}' returned {} results in {:.2f} ms (page {}/{})",
                    normalizedQuery, totalResults, searchTimeMs, page, totalPages);

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
            // Normalize query
            String normalizedQuery = query.replaceAll("(?i)\\band\\b", "AND")
            .replaceAll("(?i)\\bor\\b", "OR")
            .replaceAll("(?i)\\bnot\\b", "NOT");
            // Get the cached entry (or create one if needed)
            CacheEntry entry = getOrCreateCacheEntry(normalizedQuery);

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