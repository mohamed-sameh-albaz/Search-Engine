package com.example.searchengine.Query;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QueryController {

    private static final Logger logger = LoggerFactory.getLogger(QueryController.class);

    @Autowired
    private QueryService queryService;

    @GetMapping("/process-query")
    public ResponseEntity<String> processQuery(@RequestParam String query) {
        try {
            Map<String, List<String>> result = queryService.processQuery(query);
            StringBuilder response = new StringBuilder();
            response.append("Phrase: ").append(result.get("phrases")).append("\n");
            response.append("Stemmed: ").append(result.get("stemmed"));
            return ResponseEntity.ok(response.toString());
        } catch (Exception e) {
            logger.error("Error processing query: {}", query, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    @GetMapping("/test-queries")
    public ResponseEntity<String> testQueries() {
        try {
            StringBuilder response = new StringBuilder();
            response.append("Test Query Results:\n\n");
            
            // Test cases
            String[] testQueries = {
                "java programming",
                "\"java programming\"",
                "java \"programming language\" tutorial",
                "\"advanced programming\" \"search engine\" project",
                "java \"programming language\" \"web development\""
            };
            
            for (String testQuery : testQueries) {
                response.append("Query: ").append(testQuery).append("\n");
                Map<String, List<String>> result = queryService.processQuery(testQuery);
                response.append("Phrase: ").append(result.get("phrases")).append("\n");
                response.append("Stemmed: ").append(result.get("stemmed")).append("\n\n");
            }
            
            return ResponseEntity.ok(response.toString());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error running test queries: " + e.getMessage());
        }
    }

    @GetMapping("/query/test")
    public String testQuery(@RequestParam String q) {
        queryService.processQuery(q);
        return "Query processed. Check logs for results.";
    }
} 