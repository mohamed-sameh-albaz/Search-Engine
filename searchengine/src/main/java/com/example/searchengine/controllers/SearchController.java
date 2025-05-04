package com.example.searchengine.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.ArrayList;

import com.example.searchengine.Query.QueryService;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SearchController {

    private final QueryService queryService;
    
    @Autowired
    public SearchController(QueryService queryService) {
        this.queryService = queryService;
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
        
        formatted.put("description", description);
        
        // Include the score
        formatted.put("score", result.get("score"));
        
        return formatted;
    }
    
    // Update the actual search endpoint to use this formatter
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String query, 
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        try {
            long startTime = System.currentTimeMillis();
            
            List<Map<String, Object>> results = queryService.search(query, page, pageSize);
            
            // Format the results for the frontend
            List<Map<String, Object>> formattedResults = results.stream()
                .map(this::formatSearchResult)
                .collect(Collectors.toList());
            
            long endTime = System.currentTimeMillis();
            double searchTime = (endTime - startTime) / 1000.0;
            
            // Get total results count
            int totalResults = queryService.getTotalResultsCount(query);
            
            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("results", formattedResults);
            response.put("totalResults", totalResults);
            response.put("page", page);
            response.put("pageSize", pageSize);
            response.put("searchTime", searchTime);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    @GetMapping("/analyze-query")
    public ResponseEntity<Map<String, Object>> analyzeQuery(@RequestParam String query) {
        try {
            // Create a simple response with basic query information
            Map<String, Object> response = new HashMap<>();
            response.put("originalQuery", query);
            response.put("phrases", new ArrayList<>());
            response.put("stemmedWords", new ArrayList<>());
            response.put("operator", null);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
} 