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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Ranker.RankerMainProcess.Ranker1;

@Service
public class RankerService {

    private final Ranker1 ranker;
    private final DocumentsRepository documentsRepository;
    
    @Autowired
    public RankerService(Ranker1 ranker, DocumentsRepository documentsRepository) {
        this.ranker = ranker;
        this.documentsRepository = documentsRepository;
    }
    
    /**
     * Rank matching documents based on search terms and return paginated results
     * @param matchingDocs List of document IDs that match the search criteria
     * @param searchTerms List of search terms (stemmed)
     * @param phrases List of quoted phrases
     * @param page Page number (1-based)
     * @param size Number of results per page
     * @return List of ranked document objects with metadata
     */
    public List<Map<String, Object>> rankResults(List<Long> matchingDocs, List<String> searchTerms, 
                                               List<String> phrases, int page, int size) {
        System.out.println("Ranking results for query with " + searchTerms.size() + 
                         " terms and " + matchingDocs.size() + " matching documents");
        
        // If no matching documents, return empty list
        if (matchingDocs == null || matchingDocs.isEmpty()) {
            return Collections.emptyList();
        }

        // Convert searchTerms and phrases to the format expected by the ranker
        String[] termsArray = new String[searchTerms.size() + phrases.size()];
        int index = 0;
        
        // Add regular terms
        for (String term : searchTerms) {
            termsArray[index++] = term;
        }
        
        // Add quoted phrases (with quotes)
        for (String phrase : phrases) {
            termsArray[index++] = "\"" + phrase + "\"";
        }
        
        // Get ranked document IDs from the ranker
        int[] rankedDocIds = ranker.getFinalDocs(termsArray);
        
        // Convert to list of document objects with metadata
        List<Map<String, Object>> results = new ArrayList<>();
        double[] scores = ranker.getFinalRankScores();
        
        // Calculate pagination bounds
        int startIndex = (page - 1) * size;
        int endIndex = Math.min(startIndex + size, rankedDocIds.length);
        
        // Track already seen URLs to avoid duplicates
        Map<String, Boolean> seenUrls = new HashMap<>();
        
        // Create a map for quick document ID lookup
        Map<Long, Document> documentCache = new HashMap<>();
        
        // Get all document IDs for batch retrieval, but limit the number to avoid memory issues
        List<Long> docIdsToRetrieve = new ArrayList<>();
        int maxDocsToRetrieve = Math.min(rankedDocIds.length, endIndex + 20);
        maxDocsToRetrieve = Math.min(maxDocsToRetrieve, 500); // Hard limit to avoid OOM
        
        for (int i = 0; i < maxDocsToRetrieve; i++) {
            if (i < rankedDocIds.length) {
                docIdsToRetrieve.add((long) rankedDocIds[i]);
            }
        }
        
        // Batch retrieve documents in smaller chunks to avoid memory issues
        final int BATCH_SIZE = 50;
        for (int batchStart = 0; batchStart < docIdsToRetrieve.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, docIdsToRetrieve.size());
            List<Long> batchIds = docIdsToRetrieve.subList(batchStart, batchEnd);
            
            Iterable<Document> documents = documentsRepository.findAllById(batchIds);
            for (Document doc : documents) {
                documentCache.put(doc.getId(), doc);
            }
        }
        
        System.out.println("Preparing paginated results: page " + page + 
                         ", size " + size + ", startIndex " + startIndex + 
                         ", endIndex " + endIndex);
        
        int resultCount = 0;
        for (int i = startIndex; i < endIndex && i < rankedDocIds.length; i++) {
            // Get actual document ID
            long docId = rankedDocIds[i];
            
            // Skip negative IDs (invalid)
            if (docId < 0) {
                continue;
            }
            
            // Try to get document from cache first
            Document doc = documentCache.get(docId);
            
            // If not in cache, fetch individually
            if (doc == null) {
                Optional<Document> docOpt = documentsRepository.findById(docId);
                if (!docOpt.isPresent()) {
                    System.err.println("Document ID " + docId + " not found in database");
                    continue;
                }
                doc = docOpt.get();
            }
            
            // Check for duplicates
            String url = doc.getUrl();
            if (url == null || seenUrls.containsKey(url)) {
                continue;
            }
            seenUrls.put(url, true);
            
            // Create result object
            Map<String, Object> result = createResultObject(doc, i + 1, scores[i], termsArray);
            results.add(result);
            resultCount++;
            
            // Break if we have enough results for this page
            if (resultCount >= size) {
                break;
            }
        }
        
        return results;
    }
    
    /**
     * Create a result object with metadata for a document
     */
    private Map<String, Object> createResultObject(Document doc, int rank, double score, String[] searchTerms) {
        Map<String, Object> result = new HashMap<>();
        
        // Basic document info
        result.put("docId", doc.getId());
        result.put("url", doc.getUrl());
        result.put("title", doc.getTitle());
        result.put("rank", rank);
        result.put("score", score);
        
        // Extract and clean the description from content
        String description = getDescriptionFromContent(doc.getContent(), searchTerms);
        
        // Ensure we have a description - use alternative sources if needed
        if (description == null || description.isEmpty() || description.equals("")) {
            // Fallback to first part of content
            if (doc.getContent() != null && !doc.getContent().isEmpty()) {
                String plainContent = doc.getContent().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                description = plainContent.substring(0, Math.min(plainContent.length(), 150)) + "...";
            }
            // Last resort
            else {
                description = "Content preview unavailable";
            }
        }
        
        // Use the correct field name for the frontend - "description" vs "snippet"
        result.put("description", description);
        result.put("snippet", description); // Add both for compatibility
        
        return result;
    }
    
    /**
     * Extract a relevant description snippet from the content
     */
    private String getDescriptionFromContent(String content, String[] searchTerms) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        
        try {
            // Limit the content size to avoid memory issues
            final int MAX_CONTENT_LENGTH = 100000; // Limit to 100K characters
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }
            
            // Clean content of HTML tags
            String cleanContent = content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            
            if (cleanContent.isEmpty()) {
                return "";
            }
            
            // Extract all search terms (including those inside quotes)
            List<String> allTerms = new ArrayList<>();
            for (String term : searchTerms) {
                if (term == null) continue;
                
                // Handle quoted terms
                if (term.startsWith("\"") && term.endsWith("\"")) {
                    String innerTerm = term.substring(1, term.length() - 1);
                    if (!innerTerm.isEmpty()) {
                        allTerms.add(innerTerm);
                    }
                } else {
                    allTerms.add(term);
                }
            }
            
            // Truncate if too long
            if (cleanContent.length() > 300) {
                // Try to find context around the first search term
                int firstMatchPos = -1;
                String bestTerm = null;
                
                // First try exact matches
                for (String term : allTerms) {
                    if (term.length() <= 1) continue;
                    
                    String lowerContent = cleanContent.toLowerCase();
                    String lowerTerm = term.toLowerCase();
                    int pos = lowerContent.indexOf(lowerTerm);
                    
                    if (pos >= 0) {
                        if (firstMatchPos == -1 || pos < firstMatchPos) {
                            firstMatchPos = pos;
                            bestTerm = term;
                        }
                    }
                }
                
                // If no exact matches, try partial word matches
                if (firstMatchPos == -1) {
                    for (String term : allTerms) {
                        if (term.length() <= 2) continue; // Skip very short terms for partial matching
                        
                        // For each term, check if any word in the content contains it
                        String[] words = cleanContent.toLowerCase().split("\\s+");
                        String lowerTerm = term.toLowerCase();
                        
                        for (int i = 0; i < words.length; i++) {
                            if (words[i].contains(lowerTerm)) {
                                // Calculate position in original content
                                int wordStartPos = -1;
                                int wordCount = 0;
                                for (int j = 0; j < cleanContent.length(); j++) {
                                    if (j > 0 && Character.isWhitespace(cleanContent.charAt(j-1)) && 
                                        !Character.isWhitespace(cleanContent.charAt(j))) {
                                        wordCount++;
                                    }
                                    if (wordCount == i) {
                                        wordStartPos = j;
                                        break;
                                    }
                                }
                                
                                if (wordStartPos >= 0 && (firstMatchPos == -1 || wordStartPos < firstMatchPos)) {
                                    firstMatchPos = wordStartPos;
                                    bestTerm = words[i];
                                }
                                break;
                            }
                        }
                    }
                }
                
                // Extract context around the match
                if (firstMatchPos != -1 && bestTerm != null) {
                    int startPos = Math.max(0, firstMatchPos - 100);
                    int endPos = Math.min(cleanContent.length(), firstMatchPos + bestTerm.length() + 150);
                    
                    // Adjust to avoid cutting words
                    while (startPos > 0 && startPos < cleanContent.length() && 
                          cleanContent.charAt(startPos) != ' ' && cleanContent.charAt(startPos) != '.') {
                        startPos--;
                    }
                    
                    while (endPos < cleanContent.length() - 1 && 
                          cleanContent.charAt(endPos) != ' ' && cleanContent.charAt(endPos) != '.') {
                        endPos++;
                        // Safety check to avoid running too far
                        if (endPos - firstMatchPos > 200) {
                            break;
                        }
                    }
                    
                    String snippet = cleanContent.substring(startPos, endPos).trim();
                    
                    // Add ellipsis if we're not at the beginning/end
                    if (startPos > 0) {
                        snippet = "..." + snippet;
                    }
                    if (endPos < cleanContent.length() - 1) {
                        snippet = snippet + "...";
                    }
                    
                    return snippet;
                } else {
                    // No match found, return beginning of content
                    return cleanContent.substring(0, Math.min(250, cleanContent.length())) + "...";
                }
            } else {
                return cleanContent;
            }
        } catch (OutOfMemoryError e) {
            // Catch and handle memory errors
            return "Content too large to display preview";
        } catch (Exception e) {
            // Catch all other errors
            return "Error generating content preview";
        }
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
        
        // Create a map to track domain frequencies for increased domain diversity
        Map<String, Integer> domainFrequency = new HashMap<>();
        
        // Load document term data for top results
        for (RankedResult result : topResults) {
            Optional<Document> docOpt = documentsRepository.findById((long)result.getDocumentId());
            if (docOpt.isPresent()) {
                Document doc = docOpt.get();
                
                // Track domain frequencies
                if (doc.getUrl() != null) {
                    String domain = extractDomain(doc.getUrl().toLowerCase());
                    domainFrequency.put(domain, domainFrequency.getOrDefault(domain, 0) + 1);
                }
                
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
                
                // Add search terms to the term set to prioritize them in similarity calculation
                for (String term : searchTerms) {
                    terms.add(term.toLowerCase());
                }
                
                documentTerms.put(result.getDocumentId(), terms);
            }
        }
        
        // Apply a modified Maximal Marginal Relevance algorithm
        List<RankedResult> rerankedResults = new ArrayList<>();
        List<RankedResult> candidates = new ArrayList<>(topResults);
        
        // Always keep the top result
        if (!candidates.isEmpty()) {
            RankedResult topResult = candidates.remove(0);
            rerankedResults.add(topResult);
            
            // Update domain frequency
            Optional<Document> docOpt = documentsRepository.findById((long)topResult.getDocumentId());
            if (docOpt.isPresent() && docOpt.get().getUrl() != null) {
                String domain = extractDomain(docOpt.get().getUrl().toLowerCase());
                domainFrequency.put(domain, domainFrequency.getOrDefault(domain, 0) + 1);
            }
        }
        
        // Define domain diversity targets
        Set<String> programmingDomains = new HashSet<>(Arrays.asList(
            "github.com", "stackoverflow.com", "developer.mozilla.org", "w3schools.com",
            "freecodecamp.org", "codecademy.com", "geeksforgeeks.org", "dev.to",
            "replit.com", "codesandbox.io", "python.org", "reactjs.org"
        ));
        
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
                
                // Apply domain diversity penalties
                double domainPenalty = 0.0;
                
                Optional<Document> candidateDoc = documentsRepository.findById((long)candidate.getDocumentId());
                if (candidateDoc.isPresent() && candidateDoc.get().getUrl() != null) {
                    String candidateUrl = candidateDoc.get().getUrl().toLowerCase();
                    String candidateDomain = extractDomain(candidateUrl);
                    
                    // Check domain frequency - penalize domains that appear too often
                    int frequency = domainFrequency.getOrDefault(candidateDomain, 0);
                    if (frequency > 0) {
                        // Progressive penalty based on frequency
                        domainPenalty = Math.min(0.8, frequency * 0.15);
                    }
                    
                    // Reduce domain penalty for important programming domains
                    if (programmingDomains.contains(candidateDomain)) {
                        domainPenalty *= 0.7; // 30% reduction in penalty for programming domains
                    }
                    
                    // Additional penalty for documents with similar URL patterns
                    for (RankedResult selected : rerankedResults) {
                        Optional<Document> selectedDoc = documentsRepository.findById((long)selected.getDocumentId());
                        if (selectedDoc.isPresent() && selectedDoc.get().getUrl() != null) {
                            String selectedUrl = selectedDoc.get().getUrl().toLowerCase();
                            String selectedDomain = extractDomain(selectedUrl);
                            
                            // Check if URLs are from the same domain
                            if (candidateDomain.equals(selectedDomain)) {
                                // Check if URLs are very similar (same path structure)
                                String candidatePath = candidateUrl.replace(candidateDomain, "");
                                String selectedPath = selectedUrl.replace(selectedDomain, "");
                                
                                double urlPathSimilarity = calculatePathSimilarity(candidatePath, selectedPath);
                                if (urlPathSimilarity > 0.7) {
                                    // Strong penalty for very similar URLs from same domain
                                    domainPenalty = Math.min(0.95, domainPenalty + 0.25);
                                }
                            }
                        }
                    }
                }
                
                // Calculate final score with both content diversity and domain diversity
                double combinedScore = candidate.getScore() * (1 - diversityFactor * combinedSimilarity) * (1 - domainPenalty);
                
                // Give slight bonus to programming-focused results for programming queries
                boolean isProgrammingQuery = false;
                for (String term : searchTerms) {
                    if (term.toLowerCase().contains("code") || 
                        term.toLowerCase().contains("programming") || 
                        term.toLowerCase().contains("python") || 
                        term.toLowerCase().contains("javascript") ||
                        term.toLowerCase().contains("java") ||
                        term.toLowerCase().contains("react") ||
                        term.toLowerCase().contains("html") ||
                        term.toLowerCase().contains("ai") ||
                        term.toLowerCase().contains("css")) {
                        isProgrammingQuery = true;
                        break;
                    }
                }
                
                if (isProgrammingQuery && candidateDoc.isPresent() && candidateDoc.get().getUrl() != null) {
                    String candidateDomain = extractDomain(candidateDoc.get().getUrl().toLowerCase());
                    if (programmingDomains.contains(candidateDomain)) {
                        combinedScore *= 1.15; // 15% bonus for programming sites on programming queries
                    }
                }
                
                if (combinedScore > bestScore) {
                    bestScore = combinedScore;
                    bestCandidateIdx = i;
                }
            }
            
            if (bestCandidateIdx != -1) {
                RankedResult selected = candidates.remove(bestCandidateIdx);
                rerankedResults.add(selected);
                
                // Update domain frequency
                Optional<Document> docOpt = documentsRepository.findById((long)selected.getDocumentId());
                if (docOpt.isPresent() && docOpt.get().getUrl() != null) {
                    String domain = extractDomain(docOpt.get().getUrl().toLowerCase());
                    domainFrequency.put(domain, domainFrequency.getOrDefault(domain, 0) + 1);
                }
            } else {
                break;
            }
        }
        
        // Add remaining results in their original order
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