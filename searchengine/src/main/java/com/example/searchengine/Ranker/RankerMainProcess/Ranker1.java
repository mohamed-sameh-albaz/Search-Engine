package com.example.searchengine.Ranker.RankerMainProcess;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// import com.example.searchengine.Indexer.IndexerService;
import com.example.searchengine.Crawler.CrawlerMainProcess.CrawlerMainProcess;
import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Crawler.Repository.RelatedLinksRepository;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordDocumentMetrics;
import com.example.searchengine.Indexer.Entities.WordIdf;
import com.example.searchengine.Indexer.Repository.WordDocumentMetricsRepository;
import com.example.searchengine.Indexer.Repository.WordIdfRepository;
import com.example.searchengine.Indexer.Repository.WordRepository;
import com.example.searchengine.Indexer.Service.IndexerService;
import com.example.searchengine.Indexer.Repository.WordPositionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
public class Ranker1 {

    public static final int MAX_DOCS = 6010; // Assuming a maximum of 6000 documents
    private long[] FreqSearchTerms;
    private Map<String, Map<Long, Integer>> index;
    private long[] DocTerms; // Document term counts
    private long[][] DocTermsFreqs;
    private long numDocs;
    private long numTerms;
    private double[] RelevanceScore;
    private int[] docIdToIndex;
    // PageRank variables
    private double[] pageRankScores;
    private double dampingFactor = 0.15;
    private int maxIterations = 100;
    private double convergenceThreshold = 0.0001; // Stop when changes become small
    private int[][] adjacencyMatrix;
    private int[] outDegree;
    // Final ranking variables
    private double[] finalRankScores;
    private int[] finalDocs;
    // Weight factors - adjusted for better relevance
    private final double RELEVANCE_WEIGHT = 0.70;
    private final double PAGERANK_WEIGHT = 0.20;
    private final double TERM_DENSITY_WEIGHT = 0.10;
    
    // Word importance factors
    private final double PROGRAMMING_TERM_BOOST = 2.0;
    private final double EXACT_MATCH_BOOST = 3.0;
    private final double ALL_TERMS_BOOST = 2.5;
    private final double URL_MATCH_BOOST = 2.0;
    private final double DOMAIN_MATCH_BOOST = 3.0;
    
    // Repositories/Services
    private DocumentsRepository documentsRepository;
    private RelatedLinksRepository relatedLinksRepository;
    private CrawlerMainProcess crawlerMainProcess;
    private IndexerService indexerService;
    private WordRepository wordRepository;
    private WordIdfRepository wordIdfRepository;
    private WordDocumentMetricsRepository wordDocumentMetricsRepository;
    private WordPositionRepository wordPositionRepository;
    private JdbcTemplate jdbcTemplate;

    // Use a ranking configuration system rather than hardcoded topics
    private final Map<String, Double> domainQualityFactors = new HashMap<>();
    
    // Generic categorization of high-quality domains based on metrics, not topics
    private boolean isHighQualityDomain(String domain) {
        // Implement a more generic domain quality evaluation
        // This could look at link counts, content metrics, or external reputation
        return domainQualityFactors.getOrDefault(domain, 0.0) > 0.5;
    }
    
    // Generic term relevance without topic-specific bias
    private double getTermRelevance(String term, String query) {
        // This should evaluate relevance based on the user's query
        // rather than having a pre-defined list of important terms
        if (query.toLowerCase().contains(term.toLowerCase())) {
            return 2.0; // Direct query match is highly relevant
        }
        return 1.0; // Default relevance
    }

    @Autowired
    public Ranker1(DocumentsRepository documentsRepository, 
                  RelatedLinksRepository relatedLinksRepository,
                  CrawlerMainProcess crawlerMainProcess,
                  IndexerService indexerService,
                  WordRepository wordRepository,
                  WordIdfRepository wordIdfRepository,
                  WordDocumentMetricsRepository wordDocumentMetricsRepository,
                  WordPositionRepository wordPositionRepository,
                  JdbcTemplate jdbcTemplate) {
        this.documentsRepository = documentsRepository;
        this.relatedLinksRepository = relatedLinksRepository;
        this.crawlerMainProcess = crawlerMainProcess;
        this.indexerService = indexerService;
        this.wordRepository = wordRepository;
        this.wordIdfRepository = wordIdfRepository;
        this.wordDocumentMetricsRepository = wordDocumentMetricsRepository;
        this.wordPositionRepository = wordPositionRepository;
        this.jdbcTemplate = jdbcTemplate;
        
        // Initialize data structures
        initialize();
    }
    
    private void initialize() {
        // Get inverted index and document information
        Map<String, Map<Long, Integer>> index = indexerService.getInvertedIndex();
        Map<Long, Long> docAndTerms = indexerService.getDocumentCnt();
        Map<Long, Map<Long, Integer>> relationBetweenDocs = crawlerMainProcess.relationBetweenDocs();
        
        // Create sorted list of document IDs to ensure consistent ordering
        List<Long> sortedDocIds = new ArrayList<>(docAndTerms.keySet());
        Collections.sort(sortedDocIds);
        
        // Create mapping from document ID to array index
        docIdToIndex = new int[docAndTerms.size()];
        int idx = 0;
        for (Long docId : sortedDocIds) {
            docIdToIndex[idx++] = docId.intValue();
        }

        Map<Long, Integer> docIdToIdxMap = new HashMap<>();
        idx = 0;
        for (Long docId : sortedDocIds) {
            docIdToIdxMap.put(docId, idx++);
        }
        
        // Log the mapping for debugging
        System.out.println("Document ID to index mapping:");
        System.out.println("Total documents: " + docAndTerms.size());
        if (!sortedDocIds.isEmpty()) {
            System.out.println("First 5 document ID mappings:");
            for (int i = 0; i < Math.min(5, sortedDocIds.size()); i++) {
                System.out.println("  Index " + i + " -> Doc ID " + docIdToIndex[i]);
            }
        }

        // Create array of document term counts with the same ordering as docIdToIndex
        long[] docTermCounts = new long[docAndTerms.size()];
        for (int i = 0; i < docIdToIndex.length; i++) {
            long docId = docIdToIndex[i];
            docTermCounts[i] = docAndTerms.getOrDefault(docId, 0L);
        }

        // Build adjacency matrix for PageRank - ensure we use the correct indices
        adjacencyMatrix = new int[docAndTerms.size()][docAndTerms.size()];
        for (Map.Entry<Long, Map<Long, Integer>> entry : relationBetweenDocs.entrySet()) {
            Long docId = entry.getKey();
            Map<Long, Integer> relatedDocs = entry.getValue();
            
            // Skip if document ID is not in the map
            if (!docIdToIdxMap.containsKey(docId)) {
                continue;
            }
            
            int docIndex = docIdToIdxMap.get(docId);
            for (Map.Entry<Long, Integer> relatedDocEntry : relatedDocs.entrySet()) {
                Long relatedDocId = relatedDocEntry.getKey();
                
                // Skip if related document ID is not in the map
                if (!docIdToIdxMap.containsKey(relatedDocId)) {
                    continue;
                }
                
                int relatedDocIndex = docIdToIdxMap.get(relatedDocId);
                adjacencyMatrix[docIndex][relatedDocIndex] = 1;
            }
        }

        // Store the data
        this.index = index;
        this.DocTerms = docTermCounts;
        this.numDocs = docTermCounts.length;
        
        // Initialize PageRank variables
        this.pageRankScores = new double[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            pageRankScores[i] = 1.0 / numDocs; // Uniform initial distribution
        }
        
        // Calculate out-degree for each node
        this.outDegree = new int[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            outDegree[i] = getOutDegree(i);
        }
        calculatePageRank();
    }

    /**
     * Calculate relevance score using precomputed TF-IDF values from the database
     * This is much faster than calculating TF-IDF on the fly
     */
    public void calculateRelevanceScore(String[] searchTerms) {
        System.out.println("Calculating relevance scores using precomputed metrics...");
        long startTime = System.currentTimeMillis();
        
        RelevanceScore = new double[(int) numDocs];
        this.FreqSearchTerms = new long[searchTerms.length];
        this.DocTermsFreqs = new long[(int) numDocs][searchTerms.length];
        this.numTerms = searchTerms.length;
        
        // Create a map for quick docId to index lookup
        Map<Long, Integer> docIdToIndexMap = new HashMap<>();
        for (int i = 0; i < docIdToIndex.length; i++) {
            docIdToIndexMap.put((long) docIdToIndex[i], i);
        }
        
        // Identify quoted terms (which should be given higher priority)
        Set<String> quotedTerms = new HashSet<>();
        List<List<String>> phrases = new ArrayList<>();
        
        for (String term : searchTerms) {
            if (term.startsWith("\"") && term.endsWith("\"")) {
                String cleanTerm = term.substring(1, term.length() - 1);
                quotedTerms.add(cleanTerm);
                
                // Process phrases - split into constituent words
                List<String> phraseWords = Arrays.asList(cleanTerm.split("\\s+"));
                if (phraseWords.size() > 1) {
                    phrases.add(phraseWords);
                    System.out.println("Found phrase: " + cleanTerm + " with " + phraseWords.size() + " words");
                }
            }
        }
        
        // Process phrases using word positions
        Set<Long> documentsWithPhrases = new HashSet<>();
        if (!phrases.isEmpty()) {
            for (List<String> phrase : phrases) {
                // Find documents containing the phrase by checking word positions
                Set<Long> docsWithPhrase = findDocumentsWithPhrase(phrase);
                documentsWithPhrases.addAll(docsWithPhrase);
            }
            System.out.println("Found " + documentsWithPhrases.size() + " documents with exact phrases");
        }
        
        // Check if this is a programming query
        boolean isProgrammingQuery = false;
        for (String term : searchTerms) {
            String normalizedTerm = term.toLowerCase().replace("\"", "");
            if (getTermRelevance(normalizedTerm, String.join(" ", searchTerms)) > 1.5) {
                isProgrammingQuery = true;
                break;
            }
        }
        
        // First try to use precomputed values if available
        boolean usePrecomputedValues = true;
        
        try {
            // Create a map to store WordIdf values by term
            Map<String, Double> termIdfMap = new HashMap<>();
            
            // Find Words and IDF values for each search term
            for (int j = 0; j < numTerms; j++) {
                String term = searchTerms[j];
                // Remove quotes if present
                if (term.startsWith("\"") && term.endsWith("\"")) {
                    term = term.substring(1, term.length() - 1);
                }
                
                // Find Word entity for this term
                Optional<Word> wordOpt = wordRepository.findByWord(term.toLowerCase());
                if (!wordOpt.isPresent()) {
                    FreqSearchTerms[j] = 0; // Term not found in any document
                    continue;
                }
                
                Word word = wordOpt.get();
                
                // Find WordIdf for this word
                Optional<WordIdf> wordIdfOpt = wordIdfRepository.findByWord(word);
                if (!wordIdfOpt.isPresent()) {
                    // If IDF not precomputed, fall back to the old method
                    usePrecomputedValues = false;
                    break;
                }
                
                // Store IDF value
                WordIdf wordIdf = wordIdfOpt.get();
                double idf = wordIdf.getIdfValue();
                termIdfMap.put(term.toLowerCase(), idf);
                
                // Store term frequency for use in ranking
                FreqSearchTerms[j] = word.getTotalFrequency();
                
                // Query for TF and importance values from inverted index
                List<Object[]> results = jdbcTemplate.query(
                    "SELECT ii.doc_id, ii.frequency, ii.tf, ii.importance FROM inverted_index ii WHERE ii.word_id = ?",
                    (rs, rowNum) -> new Object[] {
                        rs.getLong("doc_id"),
                        rs.getInt("frequency"),
                        rs.getDouble("tf"),
                        rs.getInt("importance")
                    },
                    word.getId()
                );
                
                // Process each document containing this term
                for (Object[] result : results) {
                    Long docId = (Long) result[0];
                    Integer frequency = (Integer) result[1];
                    Double tf = (Double) result[2];
                    Integer importance = (Integer) result[3];
                    
                    if (!docIdToIndexMap.containsKey(docId)) {
                        continue; // Skip if document ID not in our mapping
                    }
                    
                    int docIndex = docIdToIndexMap.get(docId);
                    
                    // Store term frequency in document
                    DocTermsFreqs[docIndex][j] = frequency;
                    
                    // Calculate TF-IDF score for this term in this document
                    double tfIdf = tf * idf;
                    
                    // Apply importance multiplier
                    tfIdf *= Math.log(1 + importance);
                    
                    // Add to document's relevance score
                    RelevanceScore[docIndex] += tfIdf;
                    
                    // If this is a phrase query, boost scores for documents with exact phrase matches
                    if (!phrases.isEmpty() && documentsWithPhrases.contains(docId)) {
                        RelevanceScore[docIndex] *= EXACT_MATCH_BOOST;
                    }
                }
            }
            
            // For each document, apply boosts based on query and document characteristics
            for (int i = 0; i < numDocs; i++) {
                if (RelevanceScore[i] > 0) {
                    int docId = docIdToIndex[i];
                    applyScoreBoosts(i, searchTerms, quotedTerms, isProgrammingQuery);
                }
            }
            
            System.out.println("Relevance score calculation completed in " + (System.currentTimeMillis() - startTime) + "ms");
            
        } catch (Exception e) {
            System.err.println("Error calculating relevance scores: " + e.getMessage());
            e.printStackTrace();
            
            // Fall back to original method
            calculateRelevanceScoreOriginal(searchTerms);
        }
    }
    
    /**
     * Find documents containing an exact phrase by checking word positions
     */
    private Set<Long> findDocumentsWithPhrase(List<String> phraseWords) {
        Set<Long> documentsWithPhrase = new HashSet<>();
        
        try {
            // Find all documents containing the first word
            Optional<Word> firstWordOpt = wordRepository.findByWord(phraseWords.get(0).toLowerCase());
            if (!firstWordOpt.isPresent()) {
                return documentsWithPhrase; // No documents contain the first word
            }
            
            // Get all documents containing the first word
            List<Object[]> firstWordDocs = jdbcTemplate.query(
                "SELECT DISTINCT doc_id FROM word_position WHERE word_id = ?",
                (rs, rowNum) -> new Object[] { rs.getLong("doc_id") },
                firstWordOpt.get().getId()
            );
            
            // For each document, check if it contains the phrase in the correct order
            for (Object[] docResult : firstWordDocs) {
                Long docId = (Long) docResult[0];
                
                boolean hasPhrase = true;
                List<Long> wordIds = new ArrayList<>();
                
                // Get IDs for all words in the phrase
                for (String word : phraseWords) {
                    Optional<Word> wordOpt = wordRepository.findByWord(word.toLowerCase());
                    if (!wordOpt.isPresent()) {
                        hasPhrase = false;
                        break;
                    }
                    wordIds.add(wordOpt.get().getId());
                }
                
                if (!hasPhrase) {
                    continue; // Skip if any word not found
                }
                
                // Get all positions for the first word in this document
                List<Integer> firstWordPositions = jdbcTemplate.query(
                    "SELECT position FROM word_position WHERE word_id = ? AND doc_id = ? ORDER BY position",
                    (rs, rowNum) -> rs.getInt("position"),
                    wordIds.get(0), docId
                );
                
                // For each position of the first word, check if subsequent words appear in sequence
                for (Integer startPos : firstWordPositions) {
                    boolean matchesPhrase = true;
                    
                    // Check each subsequent word in the phrase
                    for (int i = 1; i < wordIds.size(); i++) {
                        int expectedPos = startPos + i;
                        
                        // Check if the word exists at the expected position
                        int count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM word_position WHERE word_id = ? AND doc_id = ? AND position = ?",
                            Integer.class,
                            wordIds.get(i), docId, expectedPos
                        );
                        
                        if (count == 0) {
                            matchesPhrase = false;
                            break;
                        }
                    }
                    
                    if (matchesPhrase) {
                        // Found a matching phrase!
                        documentsWithPhrase.add(docId);
                        break;
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error searching for phrase: " + e.getMessage());
        }
        
        return documentsWithPhrase;
    }
    
    /**
     * Original method for calculating relevance score (used as fallback)
     */
    private void calculateRelevanceScoreOriginal(String[] searchTerms) {
        RelevanceScore = new double[(int) numDocs];
        this.FreqSearchTerms = new long[searchTerms.length];
        this.DocTermsFreqs = new long[(int) numDocs][searchTerms.length];
        this.numTerms = searchTerms.length;
        
        // Create a map for quick docId to index lookup
        Map<Long, Integer> docIdToIndexMap = new HashMap<>();
        for (int i = 0; i < docIdToIndex.length; i++) {
            docIdToIndexMap.put((long) docIdToIndex[i], i);
        }
        
        // Identify quoted terms (which should be given higher priority)
        Set<String> quotedTerms = new HashSet<>();
        for (String term : searchTerms) {
            if (term.startsWith("\"") && term.endsWith("\"")) {
                String cleanTerm = term.substring(1, term.length() - 1);
                quotedTerms.add(cleanTerm);
            }
        }
        
        // Check if this is a programming query
        boolean isProgrammingQuery = false;
        for (String term : searchTerms) {
            String normalizedTerm = term.toLowerCase().replace("\"", "");
            if (getTermRelevance(normalizedTerm, String.join(" ", searchTerms)) > 1.5) {
                isProgrammingQuery = true;
                break;
            }
        }
        
        // Calculate term frequencies in each document
        for (int i = 0; i < numDocs; i++) {
            for (int j = 0; j < numTerms; j++) {
                Map<Long, Integer> termFreqs = index.get(searchTerms[j]);
                if (termFreqs == null) {
                    DocTermsFreqs[i][j] = 0;
                } else {
                    DocTermsFreqs[i][j] = termFreqs.getOrDefault((long) docIdToIndex[i], 0);
                }
                if (DocTermsFreqs[i][j] > 0) {
                    FreqSearchTerms[j]++;
                }
            }
        }
        
        // Calculate more effective IDF values with smoothing to avoid extremes
        double[] idfValues = new double[searchTerms.length];
        for (int j = 0; j < numTerms; j++) {
            // Apply logarithmic smoothing to avoid IDF=0 when a term appears in all documents
            // Add 1 to prevent division by zero and 1 to numerator for smoothing
            idfValues[j] = Math.log((1.0 + numDocs) / (1.0 + FreqSearchTerms[j])) + 1.0;
            
            // Apply term importance boosting for programming-specific terms
            String currentTerm = searchTerms[j];
            if (currentTerm.startsWith("\"") && currentTerm.endsWith("\"")) {
                currentTerm = currentTerm.substring(1, currentTerm.length() - 1);
            }
            
            if (getTermRelevance(currentTerm.toLowerCase(), String.join(" ", searchTerms)) > 1.5) {
                idfValues[j] *= 1.5; // Boost IDF for important programming terms
            }
            
            // Special case for very rare terms - they're likely more important
            if (FreqSearchTerms[j] <= numDocs * 0.01) { // Appears in less than 1% of docs
                idfValues[j] *= 1.2; // Additional boost for rare terms
            }
        }
        
        // Calculate TF-IDF scores with improved formula
        for (int i = 0; i < numDocs; i++) {
            RelevanceScore[i] = 0;
            for (int j = 0; j < numTerms; j++) {
                if (DocTermsFreqs[i][j] > 0) {
                    // Apply BM25-inspired TF normalization instead of simple frequency ratio
                    // This reduces the impact of term frequency saturation (diminishing returns)
                    double k1 = 1.2; // Free parameter, controls term frequency scaling
                    double b = 0.75; // Free parameter, controls document length normalization
                    
                    // Normalized document length
                    double avgDocLength = 500; // Approximate average doc length, could be calculated
                    double normDocLength = DocTerms[i] / avgDocLength;
                    
                    // BM25-inspired term frequency component
                    double tf = ((double) DocTermsFreqs[i][j] * (k1 + 1)) / 
                                (DocTermsFreqs[i][j] + k1 * (1 - b + b * normDocLength));
                    
                    // Use the pre-calculated IDF
                    double idf = idfValues[j];
                    
                    // TF-IDF score with BM25 influence
                    double tfIdf = tf * idf;
                    
                    String currentTerm = searchTerms[j];
                    // Remove quotes if present
                    if (currentTerm.startsWith("\"") && currentTerm.endsWith("\"")) {
                        currentTerm = currentTerm.substring(1, currentTerm.length() - 1);
                    }
                    
                    // Apply boost for quoted terms (exact match priority)
                    if (quotedTerms.contains(currentTerm)) {
                        tfIdf *= EXACT_MATCH_BOOST; // Triple the importance of quoted terms
                    }
                    
                    // Apply boost for programming terms in programming queries
                    if (isProgrammingQuery && getTermRelevance(currentTerm.toLowerCase(), String.join(" ", searchTerms)) > 1.5) {
                        tfIdf *= PROGRAMMING_TERM_BOOST; // Double boost for programming terms in programming queries
                    }
                    
                    // Special handling for very short terms (like "ai")
                    if (currentTerm.length() <= 2 && quotedTerms.contains(currentTerm)) {
                        tfIdf *= 2.0; // Double boost for short quoted terms
                    }
                    
                    RelevanceScore[i] += tfIdf;
                }
            }
            
            // Apply additional boosting factors
            applyScoreBoosts(i, searchTerms, quotedTerms, isProgrammingQuery);
        }
    }
    
    /**
     * Apply various boosting factors to the relevance score
     */
    private void applyScoreBoosts(int docIndex, String[] searchTerms, Set<String> quotedTerms, boolean isProgrammingQuery) {
        // Boost score if document contains all search terms (especially important for multi-term queries)
        boolean hasAllTerms = true;
        int termCount = 0;
        for (int j = 0; j < numTerms; j++) {
            if (DocTermsFreqs[docIndex][j] > 0) {
                termCount++;
            } else {
                hasAllTerms = false;
            }
        }
        
        // Scale the boost based on percentage of matching terms
        double matchRatio = (double) termCount / numTerms;
        if (matchRatio > 0.5) { // If more than 50% of terms match
            RelevanceScore[docIndex] *= (1.0 + matchRatio); // Proportional boost
        }
        
        if (hasAllTerms && numTerms > 1) {
            RelevanceScore[docIndex] *= ALL_TERMS_BOOST; // Higher boost for documents containing all terms
        }
        
        // Apply domain quality boost for programming sites
        try {
            long docId = docIdToIndex[docIndex];
            Optional<com.example.searchengine.Crawler.Entities.Document> docOpt = documentsRepository.findById(docId);
            if (docOpt.isPresent()) {
                String url = docOpt.get().getUrl();
                if (url != null) {
                    // Extract domain from URL
                    String domain = url.toLowerCase()
                        .replaceAll("https?://", "")
                        .replaceAll("www\\.", "")
                        .split("/")[0];
                    
                    // Boost score for high-quality programming domains
                    if (isHighQualityDomain(domain)) {
                        // Higher boost for programming queries on programming sites
                        if (isProgrammingQuery) {
                            RelevanceScore[docIndex] *= 2.5; // 150% boost for programming sites with programming queries
                        } else {
                            RelevanceScore[docIndex] *= 1.5; // Standard 50% boost
                        }
                    }
                    
                    // Check if URL contains any of the search terms
                    for (String term : searchTerms) {
                        // Clean term of quotes if present
                        String cleanTerm = term;
                        if (term.startsWith("\"") && term.endsWith("\"")) {
                            cleanTerm = term.substring(1, term.length() - 1);
                        }
                        
                        if (url.toLowerCase().contains(cleanTerm.toLowerCase())) {
                            RelevanceScore[docIndex] *= URL_MATCH_BOOST; // Double boost if URL contains search term
                            
                            // Extra boost for exact matches in URL for quoted terms
                            if (quotedTerms.contains(cleanTerm)) {
                                RelevanceScore[docIndex] *= 1.5; // Additional 50% boost
                            }
                            break;
                        }
                    }
                    
                    // Domain-specific boosting for domains that match query terms
                    String queryText = String.join(" ", searchTerms).toLowerCase();
                    if (domain.contains(queryText) || queryText.contains(domain)) {
                        RelevanceScore[docIndex] *= 2.0; // 100% boost for domain-query matches
                    }
                    
                    // Check for partial domain matches with query terms
                    for (String term : searchTerms) {
                        String cleanTerm = term.replaceAll("\"", "").toLowerCase();
                        if (domain.contains(cleanTerm) && cleanTerm.length() > 3) {
                            // Only boost for meaningful terms (length > 3) to avoid generic terms
                            RelevanceScore[docIndex] *= 1.5; // 50% boost for partial domain-query matches
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors in domain boost logic
        }
    }

    /**
     * Calculate term density score to favor documents with more concentrated query terms
     */
    private double[] calculateTermDensityScore(String[] searchTerms) {
        double[] termDensityScores = new double[(int) numDocs];
        
        for (int i = 0; i < numDocs; i++) {
            // Skip if document has no terms
            if (DocTerms[i] == 0) {
                continue;
            }
            
            // Calculate term density - how concentrated are the search terms in this doc
            long totalTermFreq = 0;
            for (int j = 0; j < numTerms; j++) {
                totalTermFreq += DocTermsFreqs[i][j];
            }
            
            // Term density = total occurrences of search terms / total terms in document
            double density = (double) totalTermFreq / DocTerms[i];
            
            // Proximity bonus for terms appearing close together (estimated by density)
            termDensityScores[i] = density * 100; // Scale up for better weighting
            
            // Bonus for documents with very high term density
            if (density > 0.05) { // More than 5% of terms are search terms
                termDensityScores[i] *= 1.2;
            }
        }
        
        return termDensityScores;
    }

    public void calculatePageRank() {
        double[][] transitionMatrix = new double[(int) numDocs][(int) numDocs];
        
        // Fill transition matrix
        for (int i = 0; i < numDocs; i++) {
            for (int j = 0; j < numDocs; j++) {
                // If there's a link from j to i
                if (adjacencyMatrix[j][i] > 0) {
                    transitionMatrix[i][j] = (1 - dampingFactor) * (1.0 / outDegree[j]);
                }
                // Random teleportation factor
                transitionMatrix[i][j] += dampingFactor / numDocs;
            }
        }
        
        // PageRank power iteration
        double[] newRank = new double[(int) numDocs];
        double diff = 1.0;
        int iteration = 0;
        
        while (diff > convergenceThreshold && iteration < maxIterations) {
            // Initialize new ranks to 0
            for (int i = 0; i < numDocs; i++) {
                newRank[i] = 0;
            }
            
            // Matrix multiplication: X = M.T * X
            for (int i = 0; i < numDocs; i++) {
                for (int j = 0; j < numDocs; j++) {
                    newRank[i] += transitionMatrix[i][j] * pageRankScores[j];
                }
            }
            
            // Calculate difference for convergence check
            diff = 0;
            double normOld = 0, normNew = 0;
            for (int i = 0; i < numDocs; i++) {
                normOld += pageRankScores[i] * pageRankScores[i];
                normNew += newRank[i] * newRank[i];
            }
            normOld = Math.sqrt(normOld);
            normNew = Math.sqrt(normNew);
            diff = Math.abs(normNew - normOld);
            
            // Update ranks
            System.arraycopy(newRank, 0, pageRankScores, 0, (int) numDocs);
            iteration++;
        }
        
        System.out.println("PageRank calculation completed in " + iteration + " iterations");
    }

    private int getOutDegree(int node) {
        int degree = 0;
        for (int j = 0; j < numDocs; j++) {
            if (adjacencyMatrix[node][j] > 0) {
                degree++;
            }
        }
        return degree == 0 ? 1 : degree; // Avoid division by zero
    }

    public double[] getPageRankScores() {
        return pageRankScores;
    }

    public double[] getRelevanceScore() {
        return RelevanceScore;
    }

    /**
     * Calculate final ranking by combining relevance, PageRank, and term density
     */
    public void calculateFinalRank(String[] searchTerms) {
        // Calculate individual scores
        calculateRelevanceScore(searchTerms);
        // calculatePageRank();
        double[] termDensityScores = calculateTermDensityScore(searchTerms);
        
        // Normalize scores to [0,1] range for fair weighting
        double maxRelevance = 0.000001; // Avoid division by zero
        double maxPageRank = 0.000001;
        double maxTermDensity = 0.000001;
        
        for (int i = 0; i < numDocs; i++) {
            maxRelevance = Math.max(maxRelevance, RelevanceScore[i]);
            maxPageRank = Math.max(maxPageRank, pageRankScores[i]);
            maxTermDensity = Math.max(maxTermDensity, termDensityScores[i]);
        }
        
        // Combine scores with weights
        finalRankScores = new double[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            double normalizedRelevance = RelevanceScore[i] / maxRelevance;
            double normalizedPageRank = pageRankScores[i] / maxPageRank;
            double normalizedTermDensity = termDensityScores[i] / maxTermDensity;
            
            // Check if this is likely a programming query
            boolean isProgrammingQuery = false;
            for (String term : searchTerms) {
                String normalizedTerm = term.toLowerCase().replace("\"", "");
                if (getTermRelevance(normalizedTerm, String.join(" ", searchTerms)) > 1.5) {
                    isProgrammingQuery = true;
                    break;
                }
            }
            
            // For programming queries, adjust weights to favor relevance and term density
            double relevanceWeight = RELEVANCE_WEIGHT;
            double pageRankWeight = PAGERANK_WEIGHT;
            double termDensityWeight = TERM_DENSITY_WEIGHT;
            
            if (isProgrammingQuery) {
                // For programming queries, relevance and term density are more important
                relevanceWeight += 0.05;
                termDensityWeight += 0.05;
                pageRankWeight -= 0.1;
            }
            
            finalRankScores[i] = relevanceWeight * normalizedRelevance + 
                                 pageRankWeight * normalizedPageRank +
                                 termDensityWeight * normalizedTermDensity;
        }
    }

    public double[] getFinalRankScores() {
        return finalRankScores;
    }

    /**
     * Generate final document ordering based on combined ranking
     */
    public int[] getFinalDocs(String[] searchTerms) {
        calculateFinalRank(searchTerms);
        finalDocs = new int[(int) numDocs];
        
        // Verify that docIdToIndex has been properly initialized
        if (docIdToIndex == null || docIdToIndex.length == 0) {
            System.err.println("ERROR: docIdToIndex is null or empty. Check initialization.");
            // Return empty array to avoid crashes
            return new int[0];
        }
        
        // Create pairs of (docId, score, index) and sort them
        Pair[] pairs = new Pair[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            // Verify index is within bounds
            if (i < docIdToIndex.length) {
                pairs[i] = new Pair(docIdToIndex[i], i);
                pairs[i].score = finalRankScores[i];
            } else {
                System.err.println("ERROR: Index " + i + " is out of bounds for docIdToIndex (length: " + docIdToIndex.length + ")");
                // Use a placeholder to avoid crashes
                pairs[i] = new Pair(-1, i);
                pairs[i].score = 0.0;
            }
        }
        
        // Sort by score in descending order
        Arrays.sort(pairs, new PairComparator());
        
        // Extract the sorted document IDs
        for (int i = 0; i < numDocs; i++) {
            finalDocs[i] = pairs[i].value;
        }
        
        // Logging document ID mapping for debugging
        if (numDocs > 0) {
            System.out.println("Document ID mapping sample (array index -> document ID):");
            for (int i = 0; i < Math.min(5, numDocs); i++) {
                System.out.println("  Index " + i + " -> Doc ID " + docIdToIndex[i]);
            }
            
            System.out.println("Top 5 ranked documents:");
            for (int i = 0; i < Math.min(5, numDocs); i++) {
                System.out.println("  Rank " + (i+1) + ": Doc ID " + finalDocs[i] + ", Score: " + pairs[i].score);
            }
        }
        
        return finalDocs;
    }
    
    /**
     * Comparator for ranking pairs by score
     */
    private class PairComparator implements Comparator<Pair> {
        @Override
        public int compare(Pair p1, Pair p2) {
            return Double.compare(p2.score, p1.score); // Descending order
        }
    }
    
    /**
     * Helper class for sorting documents by score
     */
    static class Pair {
        int value;  // Document ID
        int index;  // Index in the arrays
        double score; // Ranking score
        
        Pair(int value, int index) {
            this.value = value;
            this.index = index;
            this.score = 0.0;
        }
    }
}
