package com.example.searchengine.Ranker.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Ranker.RankerMainProcess.Ranker1;

@Service
public class RankerService {

    private final Ranker1 ranker;
    private final DocumentsRepository documentRepository;
    
    @Autowired
    public RankerService(Ranker1 ranker, DocumentsRepository documentRepository) {
        this.ranker = ranker;
        this.documentRepository = documentRepository;
    }
    
    /**
     * Ranks search results based on matching documents and query terms
     * 
     * @param matchingDocuments Map of query terms to matching document IDs
     * @param stemmedWords List of stemmed query words
     * @param phrases List of phrases in the query
     * @param page Current page number (1-based)
     * @param size Number of results per page
     * @return List of ranked result objects
     */
    public List<Map<String, Object>> rankResults(
            Map<String, List<Long>> matchingDocuments,
            List<String> stemmedWords,
            List<String> phrases,
            int page,
            int size) {
        
        // Get all matching document IDs
        List<Long> allMatchingDocIds = new ArrayList<>();
        for (List<Long> docIds : matchingDocuments.values()) {
            allMatchingDocIds.addAll(docIds);
        }
        
        // Remove duplicates
        List<Long> uniqueDocIds = allMatchingDocIds.stream()
                .distinct()
                .collect(Collectors.toList());
        
        // If no matches, return empty list
        if (uniqueDocIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Convert stemmed words to array for ranker
        String[] searchTerms = stemmedWords.toArray(new String[0]);
        
        // Get ranking scores using Ranker1
        ranker.calculateFinalRank(searchTerms);
        double[] scores = ranker.getFinalRankScores();
        int[] rankedDocIds = ranker.getFinalDocs(searchTerms);
        
        // Filter to only include docs that match the query
        List<RankedResult> rankedResults = new ArrayList<>();
        for (int i = 0; i < rankedDocIds.length; i++) {
            int docId = rankedDocIds[i];
            if (uniqueDocIds.contains((long)docId)) {
                rankedResults.add(new RankedResult(docId, scores[i]));
            }
        }
        
        // Sort by score (highest first)
        rankedResults.sort(Comparator.comparing(RankedResult::getScore).reversed());
        
        // Apply pagination
        int startIndex = (page - 1) * size;
        int endIndex = Math.min(startIndex + size, rankedResults.size());
        
        if (startIndex >= rankedResults.size()) {
            return Collections.emptyList();
        }
        
        List<RankedResult> pagedResults = rankedResults.subList(startIndex, endIndex);
        
        // Convert to final result format with document details
        return pagedResults.stream()
                .map(this::convertToResultMap)
                .filter(map -> map != null)
                .collect(Collectors.toList());
    }
    
    /**
     * Converts a RankedResult to a map with document details
     */
    private Map<String, Object> convertToResultMap(RankedResult rankedResult) {
        Optional<Document> optionalDoc = documentRepository.findById((long)rankedResult.getDocumentId());
        
        if (!optionalDoc.isPresent()) {
            return null;
        }
        
        Document doc = optionalDoc.get();
        Map<String, Object> result = new HashMap<>();
        
        result.put("id", doc.getId());
        result.put("url", doc.getUrl());
        result.put("title", doc.getTitle() != null ? doc.getTitle() : "No title");
        result.put("score", rankedResult.getScore());
        
        // Generate snippet from content
        result.put("snippet", generateSnippet(doc.getContent()));
        
        return result;
    }
    
    /**
     * Generates a snippet from HTML content
     */
    private String generateSnippet(String htmlContent) {
        if (htmlContent == null || htmlContent.isEmpty()) {
            return "No content available";
        }
        
        try {
            // Parse HTML and extract text
            String text = Jsoup.parse(htmlContent).text();
            
            // Get first few sentences or fixed character count
            if (text.length() > 200) {
                return text.substring(0, 197) + "...";
            } else {
                return text;
            }
        } catch (Exception e) {
            return "Content preview not available";
        }
    }
    
    /**
     * Helper class to store document ID and score
     */
    private static class RankedResult {
        private final int documentId;
        private final double score;
        
        public RankedResult(int documentId, double score) {
            this.documentId = documentId;
            this.score = score;
        }
        
        public int getDocumentId() {
            return documentId;
        }
        
        public double getScore() {
            return score;
        }
    }
} 