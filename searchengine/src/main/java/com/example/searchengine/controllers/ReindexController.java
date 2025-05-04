package com.example.searchengine.controllers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentRepository;
import com.example.searchengine.Indexer.Service.IndexerService;

@RestController
public class ReindexController {

    private final DocumentRepository documentRepository;
    private final ApplicationContext applicationContext;
    
    // Use static variables to track indexing progress to avoid circular dependencies
    private static final AtomicBoolean indexingInProgress = new AtomicBoolean(false);
    private static final AtomicInteger indexedDocumentsCount = new AtomicInteger(0);
    private static String lastIndexingStatus = "No indexing has been started";
    private static long indexingStartTime = 0;
    private static int totalDocumentsToIndex = 0;

    @Autowired
    public ReindexController(DocumentRepository documentRepository, ApplicationContext applicationContext) {
        this.documentRepository = documentRepository;
        this.applicationContext = applicationContext;
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindexDocuments() {
        
        if (indexingInProgress.get()) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "warning");
            response.put("message", "Indexing is already in progress. Check status at /index-status");
            return ResponseEntity.ok(response);
        }
        
        try {
            // Find all documents that need to be indexed
            List<Document> documents = documentRepository.findAll();
            
            if (documents.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "warning");
                response.put("message", "No documents found to index");
                return ResponseEntity.ok(response);
            }
            
            // Convert documents to a Map<String, String> of URL to Content for the indexer
            Map<String, String> documentMap = documents.stream()
                .collect(Collectors.toMap(
                    Document::getUrl,
                    Document::getContent,
                    (existing, replacement) -> existing // Keep first in case of duplicate URLs
                ));
            
            // Mark indexing as started
            indexingInProgress.set(true);
            indexingStartTime = System.currentTimeMillis();
            totalDocumentsToIndex = documents.size();
            indexedDocumentsCount.set(0);
            lastIndexingStatus = "Indexing started for " + documents.size() + " documents";
            
            // Start indexing in a separate thread
            new Thread(() -> {
                try {
                    // Get IndexerService from ApplicationContext to avoid circular dependency
                    IndexerService indexerService = applicationContext.getBean(IndexerService.class);
                    indexerService.buildIndex(documentMap);
                    lastIndexingStatus = "Indexing completed successfully for " + documents.size() + " documents in " 
                            + ((System.currentTimeMillis() - indexingStartTime) / 1000) + " seconds";
                } catch (Exception e) {
                    lastIndexingStatus = "Indexing failed: " + e.getMessage();
                } finally {
                    indexingInProgress.set(false);
                }
            }).start();
            
            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Indexing started for " + documents.size() + " documents");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "Error starting indexing: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    @GetMapping("/index-status")
    public ResponseEntity<Map<String, Object>> getIndexStatus() {
        try {
            // Get IndexerService from ApplicationContext to avoid circular dependency
            IndexerService indexerService = applicationContext.getBean(IndexerService.class);
            
            // Get statistics about the index
            Map<String, Map<Long, Integer>> index = indexerService.getInvertedIndex();
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("uniqueWords", index.size());
            
            // Count total word-document pairs
            long totalPairs = index.values().stream()
                .mapToLong(map -> map.size())
                .sum();
            stats.put("wordDocumentPairs", totalPairs);
            
            // Get document count
            long documentCount = documentRepository.count();
            stats.put("totalDocuments", documentCount);
            
            // Add indexing status
            stats.put("indexingInProgress", indexingInProgress.get());
            stats.put("lastIndexingStatus", lastIndexingStatus);
            
            if (indexingInProgress.get()) {
                stats.put("elapsedTimeSeconds", (System.currentTimeMillis() - indexingStartTime) / 1000);
                stats.put("totalDocumentsToIndex", totalDocumentsToIndex);
                stats.put("indexedDocumentsCount", indexedDocumentsCount.get());
                
                if (totalDocumentsToIndex > 0) {
                    stats.put("progressPercentage", 
                            Math.round((indexedDocumentsCount.get() * 100.0) / totalDocumentsToIndex));
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("stats", stats);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "Error getting index status: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    // Static method to update progress - can be called from anywhere
    public static void updateIndexingProgress(int documentsIndexed) {
        indexedDocumentsCount.set(documentsIndexed);
    }
} 