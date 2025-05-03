package com.example.searchengine.Query;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

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

    @GetMapping("/process-query")
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
    
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        
        try {
            // Get query result (from cache if available)
            QueryResult queryResult = getQueryResult(query);
            
            // Results storage and session handling
            String currentSessionId = sessionId;
            List<Map<String, Object>> allResults;
            
            // Special handling for Middle East conflict queries to ensure consistent ranking
            boolean isMiddleEastQuery = isMiddleEastConflictQuery(query.toLowerCase());
            
            // Check if this is an existing session and if the query matches the session's query
            boolean isValidSession = false;
            if (currentSessionId != null && searchSessionCache.containsKey(currentSessionId)) {
                SearchSession existingSession = searchSessionCache.get(currentSessionId);
                // Only reuse session if query is EXACTLY the same
                isValidSession = existingSession.query.equals(query);
                
                if (!isValidSession) {
                    // Query has changed - invalidate the old session
                    logger.info("Query changed from '{}' to '{}', creating new session", 
                              existingSession.query, query);
                    currentSessionId = null;
                }
            }
            
            // Check if we need a new search
            boolean needNewSearch = (currentSessionId == null || !isValidSession);
            
            if (needNewSearch) {
                // New search - generate session ID and calculate all results
                currentSessionId = UUID.randomUUID().toString();
                
                if (queryResult.getOperator() != null) {
                    // For complex queries, we already have the full results
                    allResults = queryResult.getResults();
                } else {
                    // For regular searches, calculate all ranked results
                    allResults = rankerService.rankResults(
                        queryResult.getMatchingDocuments(), 
                        queryResult.getStemmedWords(), 
                        queryResult.getPhrases(),
                        1,  // Start at page 1
                        Integer.MAX_VALUE  // Get all results in one go
                    );
                    
                    // Extra post-processing filter for critical queries to remove any irrelevant results
                    if (isMiddleEastQuery) {
                        // Remove results with very low scores (likely irrelevant)
                        allResults = allResults.stream()
                            .filter(result -> {
                                double score = (double) result.get("score");
                                return score > 0.05; // Filter out obviously irrelevant results
                            })
                            .collect(Collectors.toList());
                    }
                }
                
                // Store in session cache
                searchSessionCache.put(currentSessionId, new SearchSession(query, allResults));
                ensureCacheSize();
                logger.info("Created new search session {} with {} results", currentSessionId, allResults.size());
            } else {
                // Existing valid session - retrieve cached results
                SearchSession session = searchSessionCache.get(currentSessionId);
                allResults = session.results;
                logger.info("Using existing search session {} with {} results", currentSessionId, allResults.size());
            }
            
            // Store total count
            int totalResults = allResults.size();
            
            // Apply pagination to the stored results
            List<Map<String, Object>> finalResults;
            int startIndex = (page - 1) * size;
            int endIndex = Math.min(startIndex + size, totalResults);
            
            if (startIndex >= totalResults) {
                finalResults = Collections.emptyList();
            } else {
                finalResults = allResults.subList(startIndex, endIndex);
            }
            
            // Calculate pagination information
            int totalPages = (int) Math.ceil((double) totalResults / size);
            boolean hasNextPage = page < totalPages;
            boolean hasPreviousPage = page > 1;
            
            Map<String, Object> response = new HashMap<>();
            response.put("results", finalResults);
            response.put("totalResults", totalResults);
            response.put("currentPage", page);
            response.put("pageSize", size);
            response.put("totalPages", totalPages);
            response.put("hasNextPage", hasNextPage);
            response.put("hasPreviousPage", hasPreviousPage);
            response.put("sessionId", currentSessionId);
            
            // Add range information (e.g., "Showing results 11-20 of 45")
            int startItem = totalResults > 0 ? (page - 1) * size + 1 : 0;
            int endItem = Math.min(page * size, totalResults);
            response.put("startItem", startItem);
            response.put("endItem", endItem);
            
            // Add suggested queries based on frequency analysis
            if (!queryResult.getStemmedWords().isEmpty()) {
                List<String> suggestedQueries = generateSuggestedQueries(
                    queryResult.getStemmedWords(), 
                    allResults, 
                    3  // Max number of suggestions
                );
                response.put("suggestedQueries", suggestedQueries);
            }
            
            // Additional metadata
            response.put("phrases", queryResult.getPhrases());
            response.put("stemmedWords", queryResult.getStemmedWords());
            response.put("isPhraseQuery", queryResult.isPhraseQuery());
            response.put("operator", queryResult.getOperator());
            response.put("rankingFactors", getRankingFactors());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // Handle out of memory and other exceptions
            logger.error("Error processing search request: {}", e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "An error occurred while processing your search");
            errorResponse.put("results", Collections.emptyList());
            errorResponse.put("totalResults", 0);
            errorResponse.put("currentPage", page);
            errorResponse.put("pageSize", size);
            errorResponse.put("totalPages", 0);
            errorResponse.put("hasNextPage", false);
            errorResponse.put("hasPreviousPage", false);
            
            return ResponseEntity.ok(errorResponse);
        }
    }
    
    /**
     * Generates suggested alternative queries based on result analysis
     */
    private List<String> generateSuggestedQueries(List<String> originalTerms, List<Map<String, Object>> results, int maxSuggestions) {
        if (results.isEmpty() || originalTerms.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Extract frequent terms from top results
        Map<String, Integer> termFrequency = new HashMap<>();
        
        // Look at top 20 results maximum
        int maxResultsToProcess = Math.min(20, results.size());
        
        for (int i = 0; i < maxResultsToProcess; i++) {
            Map<String, Object> result = results.get(i);
            
            // Extract terms from title
            String title = (String) result.get("title");
            if (title != null) {
                String[] titleTerms = title.toLowerCase().split("\\W+");
                for (String term : titleTerms) {
                    if (term.length() > 3 && !isStopWord(term)) {
                        termFrequency.put(term, termFrequency.getOrDefault(term, 0) + 3);  // Weight title terms higher
                    }
                }
            }
            
            // Extract terms from snippet
            String snippet = (String) result.get("snippet");
            if (snippet != null) {
                String[] snippetTerms = snippet.toLowerCase().split("\\W+");
                for (String term : snippetTerms) {
                    if (term.length() > 3 && !isStopWord(term)) {
                        termFrequency.put(term, termFrequency.getOrDefault(term, 0) + 1);
                    }
                }
            }
        }
        
        // Filter out terms that are already in the original query
        for (String original : originalTerms) {
            termFrequency.remove(original.toLowerCase());
        }
        
        // Sort terms by frequency
        List<Map.Entry<String, Integer>> sortedTerms = new ArrayList<>(termFrequency.entrySet());
        sortedTerms.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        
        // Generate suggestions by combining original query with new terms
        List<String> suggestions = new ArrayList<>();
        String baseQuery = String.join(" ", originalTerms);
        
        for (int i = 0; i < Math.min(maxSuggestions, sortedTerms.size()); i++) {
            String term = sortedTerms.get(i).getKey();
            suggestions.add(baseQuery + " " + term);
        }
        
        return suggestions;
    }
    
    /**
     * Returns information about the ranking factors used
     */
    private Map<String, Object> getRankingFactors() {
        Map<String, Object> factors = new HashMap<>();
        factors.put("relevance", "Document relevance score based on TF-IDF");
        factors.put("pageRank", "Link-based importance measure");
        factors.put("termDensity", "Concentration of search terms in the document");
        factors.put("diversity", "Result diversification to reduce redundancy");
        return factors;
    }
    
    /**
     * Check if a word is a common stop word
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of("the", "and", "for", "that", "with", "this", "from", "not", "but", "you", "all", "any");
        return stopWords.contains(word.toLowerCase());
    }

    @GetMapping("/voice-search")
    public ResponseEntity<Map<String, Object>> voiceSearch(@RequestParam String query) {
        // Same as regular search but could add voice-specific logic
        return search(query, null, 1, 10);
    }

    /**
     * Determines if a query is related to the Middle East conflict
     */
    private boolean isMiddleEastConflictQuery(String query) {
        // Key terms related to the Middle East conflict
        Set<String> conflictTerms = Set.of("gaza", "israel", "palestin", "hama", "war", 
            "idf", "hostage", "netanyahu", "sinwar", "ceasefire", "houthi", "lebanon", "hezbollah");
        
        // Check if any of the conflict terms appear in the query
        for (String term : conflictTerms) {
            if (query.contains(term)) {
                return true;
            }
        }
        
        return false;
    }
} 