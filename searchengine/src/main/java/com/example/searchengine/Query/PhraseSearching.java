package com.example.searchengine.Query;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Repository.WordRepository;
import org.jsoup.Jsoup;

/**
 * Implements efficient phrase searching by:
 * 1. Finding documents containing the first meaningful word
 * 2. Checking only those documents for the complete phrase
 */
public class PhraseSearching {
    private static final Logger logger = LoggerFactory.getLogger(PhraseSearching.class);
    
    private final JdbcTemplate jdbcTemplate;
    private final WordRepository wordRepository;
    private final DocumentsRepository documentRepository;
    private final HashSet<String> stopWords;
    
    private final String phrase;
    private final List<String> queryTerms;
    
    // Results
    private final Map<Long, Double> documentScores = new HashMap<>();
    
    /**
     * Creates a new phrase search instance
     * 
     * @param phrase The exact phrase to search for
     * @param jdbcTemplate Database access
     * @param wordRepository Word repository
     * @param documentRepository Document repository
     * @param stopWords Set of stop words to ignore when finding the first meaningful word
     */
    public PhraseSearching(
            String phrase, 
            JdbcTemplate jdbcTemplate,
            WordRepository wordRepository,
            DocumentsRepository documentRepository,
            HashSet<String> stopWords) {
        
        // Make sure we have a clean phrase without quotes
        if (phrase.startsWith("\"") && phrase.endsWith("\"") && phrase.length() > 2) {
            this.phrase = phrase.substring(1, phrase.length() - 1).toLowerCase();
        } else {
            this.phrase = phrase.toLowerCase();
        }
        
        this.jdbcTemplate = jdbcTemplate;
        this.wordRepository = wordRepository;
        this.documentRepository = documentRepository;
        this.stopWords = stopWords;
        
        // Split the phrase into terms
        this.queryTerms = Arrays.asList(this.phrase.toLowerCase().split("\\s+"));
        
        // Execute the phrase search
        executeSearch();
    }
    
    /**
     * Gets the first non-stop word from the phrase
     * 
     * @return The first meaningful word
     */
    private String getFirstMeaningfulWord() {
        for (String word : queryTerms) {
            if (!word.isEmpty() && !stopWords.contains(word)) {
                return word;
            }
        }
        // If all words are stop words, return the first one
        return queryTerms.isEmpty() ? "" : queryTerms.get(0);
    }
    
    /**
     * Execute the phrase search process
     */
    private void executeSearch() {
        String firstWord = getFirstMeaningfulWord();
        if (firstWord.isEmpty()) {
            logger.warn("No meaningful words found in phrase: '{}'", phrase);
            return;
        }
        
        logger.info("Starting phrase search for '{}' using first word: '{}'", phrase, firstWord);
        
        // Find documents containing the first word
        Optional<Word> wordEntity = wordRepository.findByWord(firstWord.toLowerCase());
        if (!wordEntity.isPresent()) {
            logger.info("First word '{}' not found in any documents", firstWord);
            return;
        }
        
        // Get documents containing the first word - LIMIT results to improve performance
        final int MAX_CANDIDATE_DOCS = 1000; // Limit candidate docs to process
        List<Long> candidateDocIds = jdbcTemplate.query(
                "SELECT DISTINCT doc_id FROM inverted_index WHERE word_id = ? LIMIT ?",
                (rs, rowNum) -> rs.getLong("doc_id"),
                wordEntity.get().getId(),
                MAX_CANDIDATE_DOCS
        );
        
        logger.info("Found {} candidate documents containing word: '{}' (limited to top {})", 
                   candidateDocIds.size(), firstWord, MAX_CANDIDATE_DOCS);
        
        // Check each document for the exact phrase
        int matchCount = 0;
        final int MAX_RESULTS = 30; // Maximum results to return
        
        for (Long docId : candidateDocIds) {
            // Check if we've found enough matches already
            if (matchCount >= MAX_RESULTS) {
                logger.info("Reached maximum result count ({}), stopping search", MAX_RESULTS);
                break;
            }
            
            Optional<Document> docOpt = documentRepository.findById(docId);
            if (!docOpt.isPresent()) continue;
            
            Document doc = docOpt.get();
            boolean foundExactPhrase = checkDocumentForPhrase(doc);
            
            if (foundExactPhrase) {
                documentScores.put(docId, calculateDocumentScore(doc));
                matchCount++;
                // Reduce logging verbosity - only log every 5th match or use debug level
                if (matchCount % 5 == 0 || matchCount <= 3) {
                    logger.info("Found phrase match in document {} with title: {}", docId, doc.getTitle());
                }
            }
        }
        
        logger.info("Found {} documents containing exact phrase: '{}'", matchCount, phrase);
    }
    
    /**
     * Check if a document contains the exact phrase
     * 
     * @param document The document to check
     * @return true if the phrase is found, false otherwise
     */
    private boolean checkDocumentForPhrase(Document document) {
        if (document.getContent() == null) return false;
        
        // Parse HTML content to get clean text
        String cleanContent = Jsoup.parse(document.getContent()).text().toLowerCase();
        
        // First quick check - does the content contain the exact phrase?
        if (cleanContent.contains(phrase.toLowerCase())) {
            logger.debug("Found exact phrase in document {}", document.getId());
            return true;
        }
        
        // Title check - higher priority
        if (document.getTitle() != null && 
            document.getTitle().toLowerCase().contains(phrase.toLowerCase())) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Calculate a relevance score for the document
     * 
     * @param document Document to score
     * @return Relevance score
     */
    private double calculateDocumentScore(Document document) {
        double score = 1.0; // Base score
        
        // Boost score if phrase appears in title (high priority)
        if (document.getTitle() != null && 
            document.getTitle().toLowerCase().contains(phrase.toLowerCase())) {
            score *= 3.0;
        }
        
        // Count how many times the phrase appears in content
        if (document.getContent() != null) {
            String content = Jsoup.parse(document.getContent()).text().toLowerCase();
            
            // Count phrase occurrences
            int phraseCount = countOccurrences(content, phrase.toLowerCase());
            if (phraseCount > 1) {
                // Bonus for multiple occurrences (with diminishing returns)
                score *= (1.0 + Math.log(phraseCount));
            }
            
            // First paragraph boost
            String firstParagraph = getFirstParagraph(document.getContent());
            if (firstParagraph != null && 
                firstParagraph.toLowerCase().contains(phrase.toLowerCase())) {
                score *= 1.5; // Boost if phrase appears in first paragraph
            }
            
            // Check for phrase in URL
            if (document.getUrl() != null && 
                document.getUrl().toLowerCase().contains(phrase.toLowerCase().replace(" ", "-"))) {
                score *= 2.0; // Significant boost if phrase is in URL
            }
        }
        
        return score;
    }
    
    /**
     * Count occurrences of a substring in a text
     */
    private int countOccurrences(String text, String searchTerm) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(searchTerm, index)) != -1) {
            count++;
            index += searchTerm.length();
        }
        return count;
    }
    
    /**
     * Extract the first paragraph from HTML content
     */
    private String getFirstParagraph(String html) {
        if (html == null) return null;
        
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        org.jsoup.nodes.Element firstP = doc.select("p").first();
        return firstP != null ? firstP.text() : null;
    }
    
    /**
     * Get the results of the phrase search
     * 
     * @return Map of document IDs to relevance scores
     */
    public Map<Long, Double> getResults() {
        return documentScores;
    }
    
    /**
     * Get a list of document IDs sorted by relevance score
     * 
     * @return Sorted list of document IDs
     */
    public List<Long> getSortedDocumentIds() {
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(documentScores.entrySet());
        entries.sort(Map.Entry.<Long, Double>comparingByValue().reversed());
        
        List<Long> sortedIds = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : entries) {
            sortedIds.add(entry.getKey());
        }
        
        return sortedIds;
    }
} 