package com.example.searchengine.Query;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.searchengine.Ranker.RankerMainProcess.Ranker1;
import com.example.searchengine.Ranker.Service.RankerService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/api")
@CrossOrigin("*") // Enable CORS
@AllArgsConstructor
public class QueryController {
    
    private final QueryService queryService;
    private final RankerService rankerService;
    
    @GetMapping("/process-query")
    public ResponseEntity<Map<String, Object>> processQuery(@RequestParam String query) {
        QueryResult result = queryService.processQuery(query);
        
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
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        
        // Process query to get matching documents
        QueryResult queryResult = queryService.processQuery(query);
        
        // Check if we already have filtered results (from complex query processing)
        List<Map<String, Object>> finalResults;
        if (queryResult.getOperator() != null) {
            // For queries with operators, the QueryService has already filtered and ranked the results
            // Just apply pagination
            int totalResults = queryResult.getResults().size();
            int startIndex = (page - 1) * size;
            int endIndex = Math.min(startIndex + size, totalResults);
            
            if (startIndex >= totalResults) {
                finalResults = Collections.emptyList();
            } else {
                finalResults = queryResult.getResults().subList(startIndex, endIndex);
            }
        } else {
            // For regular queries, apply ranking as before
            finalResults = rankerService.rankResults(
                queryResult.getMatchingDocuments(), 
                queryResult.getStemmedWords(), 
                queryResult.getPhrases(),
                page, 
                size
            );
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("results", finalResults);
        response.put("totalResults", queryResult.getTotalResults());
        response.put("currentPage", page);
        response.put("pageSize", size);
        
        // Additional metadata
        response.put("phrases", queryResult.getPhrases());
        response.put("stemmedWords", queryResult.getStemmedWords());
        response.put("isPhraseQuery", queryResult.isPhraseQuery());
        response.put("operator", queryResult.getOperator());
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/voice-search")
    public ResponseEntity<Map<String, Object>> voiceSearch(@RequestParam String query) {
        // Same as regular search but could add voice-specific logic
        return search(query, 1, 10);
    }
} 