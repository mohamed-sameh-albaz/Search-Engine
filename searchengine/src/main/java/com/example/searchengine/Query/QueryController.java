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
import java.util.Comparator;

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
    
    /**
     * Ensure the session cache doesn't exceed maximum size
     */
    private void ensureSessionCacheSize() {
        if (searchSessionCache.size() > MAX_SESSION_SIZE) {
            // Remove oldest entries if cache is too large
            List<Map.Entry<String, SearchSession>> entries = new ArrayList<>(searchSessionCache.entrySet());
            entries.sort(Comparator.comparingLong(e -> e.getValue().timestamp));
            
            int toRemove = searchSessionCache.size() - MAX_SESSION_SIZE;
            for (int i = 0; i < toRemove; i++) {
                if (i < entries.size()) {
                    searchSessionCache.remove(entries.get(i).getKey());
                }
            }
        }
    }
    
    /**
     * Execute a query and get the result
     */
    private QueryResult executeQuery(String query) {
        return queryService.processQuery(query);
    }
    
    @GetMapping("/query-search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize,
            @RequestParam(required = false) String sessionId) {
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Use the cached results if available for the same session
            String newSessionId = sessionId;
            List<Map<String, Object>> allResults = null;
            
            if (sessionId != null && searchSessionCache.containsKey(sessionId)) {
                SearchSession session = searchSessionCache.get(sessionId);
                if (session.query.equals(query)) {
                    allResults = session.results;
                    logger.debug("Using cached results for session {}", sessionId);
                }
            }
            
            // If no cached results, perform the search
            if (allResults == null) {
                // Create a new session ID
                newSessionId = UUID.randomUUID().toString();
                
                QueryResult result = executeQuery(query);
                
                // Store all results in a search session for consistent pagination
                allResults = result.getResults();
                
                // Add to session cache
                ensureSessionCacheSize();
                searchSessionCache.put(newSessionId, new SearchSession(query, allResults));
            }
            
            // Calculate pagination
            int totalResults = allResults.size();
            int fromIndex = (page - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, totalResults);
            
            // Get the subset of results for the requested page
            List<Map<String, Object>> pagedResults;
            if (fromIndex < totalResults) {
                pagedResults = allResults.subList(fromIndex, toIndex);
                // Format the results to improve display quality
                pagedResults = pagedResults.stream()
                    .map(this::formatSearchResult)
                    .collect(Collectors.toList());
            } else {
                pagedResults = Collections.emptyList();
            }
            
            // Calculate elapsed time
            long elapsedTime = System.currentTimeMillis() - startTime;
            
            // Return formatted response
            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("results", pagedResults);
            response.put("total_results", totalResults);
            response.put("page", page);
            response.put("page_size", pageSize);
            response.put("session_id", newSessionId);
            response.put("time_ms", elapsedTime);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error during search", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Search failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
    
    /**
     * Format a search result for display to improve its appearance
     */
    private Map<String, Object> formatSearchResult(Map<String, Object> result) {
        Map<String, Object> formatted = new HashMap<>(result);
        
        // Format the description/snippet to make it more readable
        String description = (String) result.get("snippet");
        if (description != null) {
            // Remove any base64 or data URIs which can be long and useless
            description = description.replaceAll("data:[^\\s]*;base64,[^\\s]*", "");
            
            // Remove any JS variable declarations
            description = description.replaceAll("var\\s+[^=]+=\\s*[^;]*;", "");
            
            // Remove any CSS style blocks
            description = description.replaceAll("\\{--[^}]*\\}", "");
            
            // Remove any JSON/object literals
            description = description.replaceAll("\\{[\"'][^}]*\\}", "");
            
            // Truncate if too long
            if (description.length() > 200) {
                description = description.substring(0, 200) + "...";
            }
            
            // Clean up whitespace
            description = description.replaceAll("\\s+", " ").trim();
            
            // Update the snippet with the cleaned description
            formatted.put("snippet", description);
        }
        
        return formatted;
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
                        // Use max instead of sum
                        int currentWeight = 3;  // Weight title terms higher
                        termFrequency.put(term, Math.max(termFrequency.getOrDefault(term, 0), currentWeight));
                    }
                }
            }
            
            // Extract terms from snippet
            String snippet = (String) result.get("snippet");
            if (snippet != null) {
                String[] snippetTerms = snippet.toLowerCase().split("\\W+");
                for (String term : snippetTerms) {
                    if (term.length() > 3 && !isStopWord(term)) {
                        // Use max instead of sum
                        int currentWeight = 1;
                        termFrequency.put(term, Math.max(termFrequency.getOrDefault(term, 0), currentWeight));
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
        return search(query, 1, 10, null);
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