package com.example.searchengine.Ranker.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        
        // Apply diversity-based reranking for better result distribution
        rankedResults = applyDiversityReranking(rankedResults, searchTerms, 20);
        
        // Apply pagination - get total results first for improved pagination
        List<RankedResult> allResults = new ArrayList<>(rankedResults);
        
        // Apply pagination
        int startIndex = (page - 1) * size;
        int endIndex = Math.min(startIndex + size, rankedResults.size());
        
        if (startIndex >= rankedResults.size()) {
            return Collections.emptyList();
        }
        
        List<RankedResult> pagedResults = rankedResults.subList(startIndex, endIndex);
        
        // Convert to final result format with document details
        return pagedResults.stream()
                .map(result -> convertToResultMap(result, stemmedWords))
                .filter(map -> map != null)
                .collect(Collectors.toList());
    }
    
    /**
     * Applies diversity-based reranking to ensure varied results
     * Uses a simple greedy algorithm to promote diversity
     * 
     * @param rankedResults Original ranked results
     * @param searchTerms Search terms to consider
     * @param topK Number of top results to process for diversity (performance optimization)
     * @return Reranked results with improved diversity
     */
    private List<RankedResult> applyDiversityReranking(List<RankedResult> rankedResults, String[] searchTerms, int topK) {
        if (rankedResults.size() <= 1 || searchTerms.length <= 1) {
            return rankedResults; // No need for diversity with single result or single term
        }
        
        // Only apply diversity reranking to the top K results (for performance)
        int k = Math.min(topK, rankedResults.size());
        List<RankedResult> topResults = new ArrayList<>(rankedResults.subList(0, k));
        List<RankedResult> remainingResults = new ArrayList<>(rankedResults.subList(k, rankedResults.size()));
        
        // Create a map to store document terms for similarity calculation
        Map<Integer, Set<String>> documentTerms = new HashMap<>();
        
        // Load document term data for top results
        for (RankedResult result : topResults) {
            Optional<Document> docOpt = documentRepository.findById((long)result.getDocumentId());
            if (docOpt.isPresent()) {
                Document doc = docOpt.get();
                
                // Extract significant terms from title and content
                Set<String> terms = new HashSet<>();
                
                if (doc.getTitle() != null) {
                    Arrays.stream(doc.getTitle().toLowerCase().split("\\W+"))
                          .filter(term -> term.length() > 3) // Only significant terms
                          .forEach(terms::add);
                }
                
                if (doc.getUrl() != null) {
                    // Add URL components as signals
                    String cleanUrl = doc.getUrl().toLowerCase()
                        .replaceAll("https?://", "")
                        .replaceAll("www\\.", "");
                    
                    Arrays.stream(cleanUrl.split("[/.-]"))
                          .filter(term -> term.length() > 3)
                          .forEach(terms::add);
                }
                
                if (doc.getContent() != null) {
                    // Extract a sample of terms from content (for efficiency)
                    String text = Jsoup.parse(doc.getContent()).text().toLowerCase();
                    String[] contentTerms = text.split("\\W+");
                    for (int i = 0; i < Math.min(300, contentTerms.length); i++) {
                        if (contentTerms[i].length() > 3) {
                            terms.add(contentTerms[i]);
                        }
                    }
                }
                
                documentTerms.put(result.getDocumentId(), terms);
            }
        }
        
        // Apply a modified Maximal Marginal Relevance algorithm
        List<RankedResult> rerankedResults = new ArrayList<>();
        List<RankedResult> candidates = new ArrayList<>(topResults);
        
        // Always keep the top result
        if (!candidates.isEmpty()) {
            rerankedResults.add(candidates.remove(0));
        }
        
        // Greedy selection for diversity
        while (!candidates.isEmpty() && rerankedResults.size() < k) {
            int bestCandidateIdx = -1;
            double bestScore = -1;
            
            for (int i = 0; i < candidates.size(); i++) {
                RankedResult candidate = candidates.get(i);
                Set<String> candidateTerms = documentTerms.get(candidate.getDocumentId());
                
                if (candidateTerms == null) {
                    continue;
                }
                
                // Calculate similarity to already selected documents
                double maxSimilarity = 0;
                double avgSimilarity = 0;
                int similarityCount = 0;
                
                for (RankedResult selected : rerankedResults) {
                    Set<String> selectedTerms = documentTerms.get(selected.getDocumentId());
                    
                    if (selectedTerms != null) {
                        double similarity = calculateJaccardSimilarity(candidateTerms, selectedTerms);
                        maxSimilarity = Math.max(maxSimilarity, similarity);
                        avgSimilarity += similarity;
                        similarityCount++;
                    }
                }
                
                // Use both max and average similarity
                if (similarityCount > 0) {
                    avgSimilarity /= similarityCount;
                }
                
                // Balance between max and average similarity for better diversity
                double combinedSimilarity = 0.7 * maxSimilarity + 0.3 * avgSimilarity;
                
                // Calculate the combined score (original score - similarity penalty)
                double diversityFactor = 0.7; // Increased from 0.5 for even stronger diversity
                
                // Apply stronger penalty to very similar documents
                if (maxSimilarity > 0.7) {
                    diversityFactor = 0.9; // Even stronger penalty for near-duplicates
                }
                
                // Additional penalty for documents with similar URL patterns
                Optional<Document> candidateDoc = documentRepository.findById((long)candidate.getDocumentId());
                if (candidateDoc.isPresent() && candidateDoc.get().getUrl() != null) {
                    String candidateUrl = candidateDoc.get().getUrl().toLowerCase();
                    
                    for (RankedResult selected : rerankedResults) {
                        Optional<Document> selectedDoc = documentRepository.findById((long)selected.getDocumentId());
                        if (selectedDoc.isPresent() && selectedDoc.get().getUrl() != null) {
                            String selectedUrl = selectedDoc.get().getUrl().toLowerCase();
                            
                            // Check if URLs are from the same domain
                            String candidateDomain = extractDomain(candidateUrl);
                            String selectedDomain = extractDomain(selectedUrl);
                            
                            if (candidateDomain.equals(selectedDomain)) {
                                // Check if URLs are very similar (same path structure)
                                String candidatePath = candidateUrl.replace(candidateDomain, "");
                                String selectedPath = selectedUrl.replace(selectedDomain, "");
                                
                                double urlPathSimilarity = calculatePathSimilarity(candidatePath, selectedPath);
                                if (urlPathSimilarity > 0.7) {
                                    // Strong penalty for very similar URLs from same domain
                                    diversityFactor = Math.min(0.95, diversityFactor + 0.15);
                                }
                            }
                        }
                    }
                }
                
                double combinedScore = candidate.getScore() * (1 - diversityFactor * combinedSimilarity);
                
                if (combinedScore > bestScore) {
                    bestScore = combinedScore;
                    bestCandidateIdx = i;
                }
            }
            
            if (bestCandidateIdx >= 0) {
                rerankedResults.add(candidates.remove(bestCandidateIdx));
            } else {
                // Fallback if no good candidate found
                rerankedResults.add(candidates.remove(0));
            }
        }
        
        // Combine reranked results with remaining results
        rerankedResults.addAll(remainingResults);
        
        return rerankedResults;
    }
    
    /**
     * Calculates Jaccard similarity between two sets of terms
     */
    private double calculateJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1 == null || set2 == null || set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        return (double) intersection.size() / union.size();
    }
    
    /**
     * Extracts domain from URL
     */
    private String extractDomain(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        
        // Remove protocol
        String domain = url.replaceAll("^https?://", "");
        
        // Extract domain (everything before first / or end of string)
        int slashIndex = domain.indexOf('/');
        if (slashIndex > 0) {
            domain = domain.substring(0, slashIndex);
        }
        
        // Remove www prefix
        domain = domain.replaceAll("^www\\.", "");
        
        return domain;
    }
    
    /**
     * Calculates similarity between URL paths
     */
    private double calculatePathSimilarity(String path1, String path2) {
        if (path1 == null || path2 == null) {
            return 0.0;
        }
        
        // Normalize paths: remove trailing slashes and query parameters
        path1 = path1.replaceAll("/*$", "").replaceAll("\\?.*$", "");
        path2 = path2.replaceAll("/*$", "").replaceAll("\\?.*$", "");
        
        if (path1.isEmpty() || path2.isEmpty()) {
            return 0.0;
        }
        
        // Convert paths to segments
        String[] segments1 = path1.split("/");
        String[] segments2 = path2.split("/");
        
        // Calculate segment-wise similarity
        int matchingSegments = 0;
        int maxSegments = Math.max(segments1.length, segments2.length);
        
        for (int i = 0; i < Math.min(segments1.length, segments2.length); i++) {
            if (segments1[i].equals(segments2[i])) {
                matchingSegments++;
            }
        }
        
        if (maxSegments == 0) {
            return 0.0;
        }
        
        return (double) matchingSegments / maxSegments;
    }
    
    /**
     * Converts a RankedResult to a map with document details
     */
    private Map<String, Object> convertToResultMap(RankedResult rankedResult, List<String> stemmedWords) {
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
        
        // Generate more descriptive snippet from content
        result.put("snippet", generateHighlightedSnippet(doc.getContent(), stemmedWords));
        
        return result;
    }
    
    /**
     * Generates a snippet from HTML content with query term highlighting
     */
    private String generateHighlightedSnippet(String htmlContent, List<String> queryTerms) {
        if (htmlContent == null || htmlContent.isEmpty()) {
            return "No content available";
        }
        
        try {
            // Parse HTML and extract text
            String text = Jsoup.parse(htmlContent).text();
            
            // Look for a paragraph containing the query terms
            String[] paragraphs = text.split("\\. ");
            String bestParagraph = null;
            int maxTerms = 0;
            
            for (String paragraph : paragraphs) {
                if (paragraph.length() < 20) continue; // Skip very short paragraphs
                
                String lowerParagraph = paragraph.toLowerCase();
                int termCount = 0;
                
                for (String term : queryTerms) {
                    if (lowerParagraph.contains(term.toLowerCase())) {
                        termCount++;
                    }
                }
                
                if (termCount > maxTerms) {
                    maxTerms = termCount;
                    bestParagraph = paragraph;
                }
            }
            
            // If no paragraph contains query terms, use the beginning
            if (bestParagraph == null) {
                if (text.length() > 200) {
                    return text.substring(0, 197) + "...";
                } else {
                    return text;
                }
            }
            
            // Truncate if necessary
            if (bestParagraph.length() > 200) {
                return bestParagraph.substring(0, 197) + "...";
            } else {
                return bestParagraph;
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