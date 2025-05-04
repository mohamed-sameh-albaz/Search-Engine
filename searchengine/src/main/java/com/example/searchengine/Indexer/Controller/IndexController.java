package com.example.searchengine.Indexer.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentRepository;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Repository.WordRepository;
import com.example.searchengine.Indexer.Service.IndexerService;

@RestController
@RequestMapping("/api/indexer")
@CrossOrigin("*")
public class IndexController {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private IndexerService indexService;

    @Autowired
    WordRepository wordRepository;

    @GetMapping("/index")
    public Map<String, Map<String, Object>> getIndex() {
        return indexService.getIndex();
    }

    @GetMapping("/")
    public List<Word> getWords() {
        return wordRepository.findAll(PageRequest.of(0, 50)).getContent();
    }

    @PostMapping("/word")
    public Word postMethodName(@RequestBody Word word) {
        return wordRepository.save(word);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex(@RequestParam(required = false) List<String> urls) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (urls != null && !urls.isEmpty()) {
                // Reindex specific URLs - this is fine since it's a small list
                Map<String, String> documentsToIndex = new HashMap<>();
                for (String url : urls) {
                    Document doc = documentRepository.findByUrl(url).orElse(null);
                    if (doc != null && doc.getContent() != null) {
                        documentsToIndex.put(doc.getUrl(), doc.getContent());
                    }
                }
                
                if (!documentsToIndex.isEmpty()) {
                    indexService.buildIndex(documentsToIndex);
                    response.put("message", "Reindexed " + documentsToIndex.size() + " specific documents");
                    response.put("status", "success");
                } else {
                    response.put("message", "No documents found with the specified URLs");
                    response.put("status", "warning");
                }
            } else {
                // For indexing all documents, we'll process in small batches to avoid memory issues
                long totalDocuments = documentRepository.count();
                int batchSize = 100; // Increased batch size for better performance
                long totalBatches = (totalDocuments + batchSize - 1) / batchSize; // Ceiling division
                
                response.put("message", "Started reindexing all " + totalDocuments + 
                             " documents in " + totalBatches + " batches. Each batch will process " + 
                             batchSize + " documents.");
                response.put("status", "success");
                response.put("totalDocuments", totalDocuments);
                response.put("totalBatches", totalBatches);
                
                // Use an AtomicInteger for thread-safe progress tracking
                final AtomicInteger processedBatches = new AtomicInteger(0);
                final AtomicInteger processedDocuments = new AtomicInteger(0);
                
                // Start the reindexing process in a separate thread
                new Thread(() -> {
                    try {
                        System.out.println("Starting background reindexing of all documents...");
                        int page = 0;
                        boolean hasMore = true;
                        
                        while (hasMore) {
                            // Process one batch at a time
                            Map<String, String> batch = new HashMap<>();
                            Page<Document> documentPage = documentRepository.findAll(PageRequest.of(page, batchSize));
                            
                            if (documentPage.isEmpty()) {
                                hasMore = false;
                                continue;
                            }
                            
                            for (Document doc : documentPage.getContent()) {
                                if (doc.getUrl() != null && doc.getContent() != null) {
                                    batch.put(doc.getUrl(), doc.getContent());
                                }
                            }
                            
                            // Index this batch
                            if (!batch.isEmpty()) {
                                indexService.buildIndex(batch);
                                processedDocuments.addAndGet(batch.size());
                                int currentBatch = processedBatches.incrementAndGet();
                                
                                // Log progress
                                System.out.println(String.format(
                                    "Indexed batch %d of %d (%.1f%%) - %d/%d documents processed",
                                    currentBatch, totalBatches,
                                    (currentBatch * 100.0 / totalBatches),
                                    processedDocuments.get(), totalDocuments
                                ));
                            }
                            
                            page++;
                            
                            // Clear the batch and help GC
                            batch.clear();
                            System.gc();
                            
                            // A small delay between batches to prevent overwhelming the system
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        
                        // After completing indexing, compute metrics for better search performance
                        indexService.computeAndStoreMetrics();
                        
                        System.out.println("Background reindexing completed: " + processedDocuments.get() + 
                                           " documents processed. Search metrics computed.");
                    } catch (Exception e) {
                        System.err.println("Error during background reindexing: " + e.getMessage());
                        e.printStackTrace();
                    }
                }).start();
            }
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Error during reindexing: " + e.getMessage());
            e.printStackTrace();
        }
        
        return response;
    }

    @GetMapping("/invertedIndex")
    public Map<String, Map<Long, Integer>> getMethodName() {
        return indexService.getInvertedIndex();
    }

    @GetMapping("/documentWords")
    public Map<Long, Long> getDocumentWordsCnt() {
        return indexService.getDocumentCnt();
    }

    @GetMapping("/indexStatus")
    public Map<String, Object> getIndexStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // Get word count
        long wordCount = wordRepository.count();
        status.put("wordCount", wordCount);
        
        // Get document count
        long documentCount = documentRepository.count();
        status.put("documentCount", documentCount);
        
        // Get sample of recent words
        List<Word> recentWords = wordRepository.findAll(PageRequest.of(0, 10, 
                org.springframework.data.domain.Sort.by("id").descending())).getContent();
        
        status.put("recentWords", recentWords.stream()
                .map(Word::getWord)
                .collect(java.util.stream.Collectors.toList()));
                
        return status;
    }

    /**
     * Endpoint to trigger the computation of search metrics (IDF, TF-IDF) for faster search
     * This should be called after indexing a significant number of new documents
     */
    @PostMapping("/compute-metrics")
    public ResponseEntity<Map<String, Object>> computeMetrics() {// a function 
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Start async metrics computation
            indexService.computeAndStoreMetrics();
            response.put("success", true);
            response.put("message", "Metrics computation started successfully. This is an asynchronous operation and may take some time to complete.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to start metrics computation: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * Endpoint to get indexing statistics and information
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getIndexStats() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Get document count
            long documentCount = indexService.getDocumentCnt().size();
            response.put("documentCount", documentCount);
            
            // Get word count
            long wordCount = indexService.getInvertedIndex().size();
            response.put("wordCount", wordCount);
            
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to get index stats: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

}