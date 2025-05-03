package com.example.searchengine.Ranker.RankerMainProcess;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// import com.example.searchengine.Indexer.IndexerService;
import com.example.searchengine.Crawler.CrawlerMainProcess.CrawlerMainProcess;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Crawler.Repository.RelatedLinksRepository;
import com.example.searchengine.Indexer.Service.IndexerService;
import com.example.searchengine.Crawler.Repository.RelatedLinksRepository;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Crawler.CrawlerMainProcess.CrawlerMainProcess;
import com.example.searchengine.Indexer.Service.IndexerService; 

@Component
public class Ranker1 {

    public static final int MAX_DOCS = 6010; // Assuming a maximum of 6000 documents
    private long[] FreqSearchTerms;
    private Map<String, Map<Long, Integer>> index;
    private long[] DocTerms = new long[MAX_DOCS]; // Document term counts
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
    private final double RELEVANCE_WEIGHT = 0.75;
    private final double PAGERANK_WEIGHT = 0.15;
    private final double TERM_DENSITY_WEIGHT = 0.10;
    
    // Repositories/Services
    private DocumentsRepository documentsRepository;
    private RelatedLinksRepository relatedLinksRepository;
    private CrawlerMainProcess crawlerMainProcess;
    private IndexerService indexerService;

    @Autowired
    public Ranker1(DocumentsRepository documentsRepository, 
                  RelatedLinksRepository relatedLinksRepository,
                  CrawlerMainProcess crawlerMainProcess,
                  IndexerService indexerService) {
        this.documentsRepository = documentsRepository;
        this.relatedLinksRepository = relatedLinksRepository;
        this.crawlerMainProcess = crawlerMainProcess;
        this.indexerService = indexerService;
        
        // Initialize data structures
        initialize();
    }
    
    private void initialize() {
        // Get inverted index and document information
        Map<String, Map<Long, Integer>> index = indexerService.getInvertedIndex();
        Map<Long, Long> docAndTerms = indexerService.getDocumentCnt();
        Map<Long, Map<Long, Integer>> relationBetweenDocs = crawlerMainProcess.relationBetweenDocs();
        
        // Create mapping from document ID to array index
        docIdToIndex = new int[docAndTerms.size()];
        int idx = 0;
        for (Long docId : docAndTerms.keySet()) {
            docIdToIndex[idx++] = docId.intValue();
        }

        Map<Long, Integer> docIdToIdxMap = new java.util.HashMap<>();
        idx = 0;
        for (Long docId : docAndTerms.keySet()) {
            docIdToIdxMap.put(docId, idx++);
        }

        // Create array of document term counts
        long[] docTermCounts = new long[docAndTerms.size()];
        idx = 0;
        for (Long count : docAndTerms.values()) {
            docTermCounts[idx++] = count;
        }

        // Build adjacency matrix for PageRank
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
    }

    /**
     * Calculate relevance score (TF-IDF) for the search terms
     */
    public void calculateRelevanceScore(String[] searchTerms) {
        RelevanceScore = new double[(int) numDocs];
        this.FreqSearchTerms = new long[searchTerms.length];
        this.DocTermsFreqs = new long[(int) numDocs][searchTerms.length];
        this.numTerms = searchTerms.length;
        
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
        
        // Calculate TF-IDF scores
        for (int i = 0; i < numDocs; i++) {
            RelevanceScore[i] = 0;
            for (int j = 0; j < numTerms; j++) {
                if (DocTermsFreqs[i][j] > 0) {
                    // Term frequency normalized by document length
                    double tf = ((double) DocTermsFreqs[i][j] / (DocTerms[i] == 0 ? 1 : (double) DocTerms[i]));
                    // Inverse document frequency
                    double idf = Math.log((double) numDocs / (FreqSearchTerms[j] == 0 ? 1 : (double) FreqSearchTerms[j]));
                    // TF-IDF score
                    RelevanceScore[i] += (tf * idf);
                }
            }
            
            // Boost score if document contains all search terms
            boolean hasAllTerms = true;
            for (int j = 0; j < numTerms; j++) {
                if (DocTermsFreqs[i][j] == 0) {
                    hasAllTerms = false;
                    break;
                }
            }
            
            if (hasAllTerms && numTerms > 1) {
                RelevanceScore[i] *= 1.5; // 50% boost for documents containing all terms
            }
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
        calculatePageRank();
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
            
            finalRankScores[i] = RELEVANCE_WEIGHT * normalizedRelevance + 
                                PAGERANK_WEIGHT * normalizedPageRank +
                                TERM_DENSITY_WEIGHT * normalizedTermDensity;
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
        
        // Create pairs of (score, index) and sort them
        Pair[] pairs = new Pair[(int) numDocs];
        for (int i = 0; i < numDocs; i++) {
            pairs[i] = new Pair(docIdToIndex[i], i);
            pairs[i].score = finalRankScores[i];
        }
        
        // Sort by score in descending order
        Arrays.sort(pairs, new PairComparator());
        
        // Extract the sorted document IDs
        for (int i = 0; i < numDocs; i++) {
            finalDocs[i] = pairs[i].value;
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
