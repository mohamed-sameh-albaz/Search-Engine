package com.example.searchengine.Query;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Indexer.Entities.InvertedIndex;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordDocumentTag;
import com.example.searchengine.Indexer.Repository.InvertedIndexRepository;
import com.example.searchengine.Indexer.Repository.WordRepository;
import com.example.searchengine.Indexer.Repository.WordDocumentTagRepository;
import com.example.searchengine.Indexer.Service.PreIndexer;

// Add OpenNLP Porter Stemmer
import opennlp.tools.stemmer.PorterStemmer;
import org.jsoup.Jsoup;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.example.searchengine.config.SearchConfig;

@Service
public class QueryService {
    
    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);
    private final HashSet<String> stopWords;
    private final Analyzer analyzer;
    private final PreIndexer preIndexer;
    private final WordRepository wordRepository;
    private final DocumentsRepository documentRepository;
    private final InvertedIndexRepository invertedIndexRepository;
    private final WordDocumentTagRepository wordDocumentTagRepository;
    private final JdbcTemplate jdbcTemplate;
    
    // Add Porter stemmer as field
    private final PorterStemmer porterStemmer;

    private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("\\s+(AND|OR|NOT)\\s+", Pattern.CASE_INSENSITIVE);

    @Autowired
    public QueryService(PreIndexer preIndexer, 
                       WordRepository wordRepository,
                       DocumentsRepository documentRepository,
                       InvertedIndexRepository invertedIndexRepository,
                       WordDocumentTagRepository wordDocumentTagRepository,
                       JdbcTemplate jdbcTemplate) {
        this.stopWords = getStopWords();
        this.analyzer = new StandardAnalyzer();
        this.preIndexer = preIndexer;
        this.wordRepository = wordRepository;
        this.documentRepository = documentRepository;
        this.invertedIndexRepository = invertedIndexRepository;
        this.wordDocumentTagRepository = wordDocumentTagRepository;
        this.jdbcTemplate = jdbcTemplate;
        
        // Initialize Porter stemmer
        this.porterStemmer = new PorterStemmer();
    }

    public QueryResult processQuery(String query) {
        logger.info("Processing query: {}", query);
        
        QueryResult result = new QueryResult();
        result.setOriginalQuery(query);
        
        try {
            List<String> phrases = new ArrayList<>();
            List<String> stemmedWords = new ArrayList<>();
            Map<String, List<Long>> matchingDocuments = new HashMap<>();
            
            // Check if the query contains operators (AND, OR, NOT)
            Matcher operatorMatcher = OPERATOR_PATTERN.matcher(query);
            if (operatorMatcher.find()) {
                // This is a complex query with operators
                processComplexQuery(query, result);
                return result;
            }
            
            // Simple query processing (existing code)
            boolean isPhraseQuery = false;
            
            // If the entire query is within quotes
            if (query.startsWith("\"") && query.endsWith("\"")) {
                query = query.substring(1, query.length() - 1);
                isPhraseQuery = true;
                result.setPhraseQuery(true);
            }
            
            // Extract phrases (text between quotes) if not already a phrase query
            if (!isPhraseQuery) {
                // Match quoted phrases with regex pattern
                Matcher matcher = PHRASE_PATTERN.matcher(query);
                
                while (matcher.find()) {
                    String phrase = matcher.group(1).trim();
                    if (!phrase.isEmpty()) {
                        phrases.add(phrase);
                    }
                }
                
                // Process non-phrase parts
                String nonPhraseParts = query.replaceAll("\"[^\"]*\"", " ").trim();
                processRegularWords(nonPhraseParts, stemmedWords, matchingDocuments);
            } else {
                // The entire query is a phrase
                phrases.add(query);
            }
            
            // Process phrases and get matching documents
            for (String phrase : phrases) {
                processPhraseSearch(phrase, matchingDocuments);
            }
            
            result.setPhrases(phrases);
            result.setStemmedWords(stemmedWords);
            result.setMatchingDocuments(matchingDocuments);
            
            // Fetch the actual document results with snippets
            List<Map<String, Object>> searchResults;
            if (!isPhraseQuery || phrases.isEmpty()) {
                searchResults = fetchRegularSearchResults(stemmedWords, matchingDocuments);
            } else {
                searchResults = fetchPhraseSearchResults(phrases.get(0), matchingDocuments);
            }
            
            // Apply additional post-processing filters
            if (!searchResults.isEmpty()) {
                // Filter out low-quality results and e-commerce pages
                searchResults = filterLowQualityResults(searchResults, stemmedWords, phrases);
                
                // Generate relevant suggested queries
                result.setSuggestedQueries(generateSuggestedQueries(query, searchResults));
            }
            
            result.setResults(searchResults);
            
            logger.info("Query processing completed with {} results", result.getResults().size());
            
        } catch (Exception e) {
            logger.error("Error processing query: {}", query, e);
            result.setErrorMessage("An error occurred while processing your query");
        }
        
        return result;
    }
    
    private void processComplexQuery(String query, QueryResult result) {
        logger.info("Processing complex query with operators: {}", query);
        
        // Parse the query into parts
        String[] parts = OPERATOR_PATTERN.split(query);
        
        if (parts.length != 2) {
            logger.warn("Complex query has more than one operator, only supporting two parts: {}", query);
            result.setErrorMessage("Complex queries support at most one operator (AND/OR/NOT)");
            return;
        }
        
        String leftPart = parts[0].trim();
        String rightPart = parts[1].trim();
        
        // Extract the operator (AND, OR, NOT)
        Matcher operatorMatcher = OPERATOR_PATTERN.matcher(query);
        operatorMatcher.find();
        String operator = operatorMatcher.group(1).toUpperCase();
        
        // Process left and right parts separately
        Map<String, List<Long>> leftDocuments = new HashMap<>();
        List<String> leftPhrases = new ArrayList<>();
        List<String> leftStemmedWords = new ArrayList<>();
        processQueryPart(leftPart, leftPhrases, leftStemmedWords, leftDocuments);
        
        Map<String, List<Long>> rightDocuments = new HashMap<>();
        List<String> rightPhrases = new ArrayList<>();
        List<String> rightStemmedWords = new ArrayList<>();
        processQueryPart(rightPart, rightPhrases, rightStemmedWords, rightDocuments);
        
        // Combine results based on the operator
        Map<String, List<Long>> combinedDocuments = new HashMap<>();
        List<String> combinedPhrases = new ArrayList<>();
        List<String> combinedStemmedWords = new ArrayList<>();
        
        // Combine the phrases and stemmed words
        combinedPhrases.addAll(leftPhrases);
        combinedPhrases.addAll(rightPhrases);
        combinedStemmedWords.addAll(leftStemmedWords);
        combinedStemmedWords.addAll(rightStemmedWords);
        
        // Get all document IDs from left and right parts
        Set<Long> leftDocIds = getUniqueDocIds(leftDocuments);
        Set<Long> rightDocIds = getUniqueDocIds(rightDocuments);
        
        logger.info("Left part '{}' matched {} documents", leftPart, leftDocIds.size());
        logger.info("Right part '{}' matched {} documents", rightPart, rightDocIds.size());
        
        // Get the combined document set based on the operator
        Set<Long> resultDocIds = new HashSet<>();
        
        switch (operator) {
            case "AND":
                leftDocIds.retainAll(rightDocIds);  // Intersection
                resultDocIds = leftDocIds;
                break;
            case "OR":
                resultDocIds.addAll(leftDocIds);    // Union
                resultDocIds.addAll(rightDocIds);
                break;
            case "NOT":
                // For NOT, we need to filter out right docs from left docs
                // Get all documents that contain the left term but not the right term
                for (Long docId : leftDocIds) {
                    if (!rightDocIds.contains(docId)) {
                        resultDocIds.add(docId);
                    }
                }
                break;
            default:
                logger.warn("Unknown operator: {}", operator);
                break;
        }
        
        logger.info("After {} operation, result has {} documents", operator, resultDocIds.size());
        
        // Create a combined entry for the complete query
        combinedDocuments.put(query, new ArrayList<>(resultDocIds));
        
        // Add individual parts to the combined documents
        combinedDocuments.putAll(leftDocuments);
        combinedDocuments.putAll(rightDocuments);
        
        // Set the result properties
        result.setPhrases(combinedPhrases);
        result.setStemmedWords(combinedStemmedWords);
        result.setMatchingDocuments(combinedDocuments);
        result.setOperator(operator);
        
        // Fetch the actual document results
        List<Map<String, Object>> results = fetchDocumentDetails(combinedStemmedWords, new ArrayList<>(resultDocIds));
        
        // Additional check for NOT operator - verify none of the results contain the negated term
        // This is necessary because the database might not have properly indexed all terms
        if (operator.equals("NOT") && (!rightPhrases.isEmpty() || !rightStemmedWords.isEmpty())) {
            List<Map<String, Object>> filteredResults = new ArrayList<>();
            
            for (Map<String, Object> doc : results) {
                Long docId = ((Number)doc.get("id")).longValue();
                Optional<Document> documentOpt = documentRepository.findById(docId);
                
                if (documentOpt.isPresent()) {
                    Document document = documentOpt.get();
                    boolean containsNegatedTerm = false;
                    
                    // Check all phrases to exclude
                    for (String phrase : rightPhrases) {
                        if (documentContainsTermOrPhrase(document, phrase)) {
                            containsNegatedTerm = true;
                            break;
                        }
                    }
                    
                    // Also check individual words to exclude
                    if (!containsNegatedTerm && !rightStemmedWords.isEmpty()) {
                        for (String word : rightStemmedWords) {
                            if (documentContainsTermOrPhrase(document, word)) {
                                containsNegatedTerm = true;
                                break;
                            }
                        }
                    }
                    
                    if (!containsNegatedTerm) {
                        filteredResults.add(doc);
                    }
                }
            }
            
            // Sort the results by relevance score
            filteredResults.sort((doc1, doc2) -> 
                Double.compare(
                    ((Number)doc2.get("score")).doubleValue(), 
                    ((Number)doc1.get("score")).doubleValue()
                )
            );
            
            result.setResults(filteredResults);
            logger.info("After content-based filtering for NOT, final result has {} documents", filteredResults.size());
        } else {
            // Sort the results by relevance score
            results.sort((doc1, doc2) -> 
                Double.compare(
                    ((Number)doc2.get("score")).doubleValue(), 
                    ((Number)doc1.get("score")).doubleValue()
                )
            );
            
            result.setResults(results);
        }
        
        logger.info("Complex query processing completed with {} results", result.getResults().size());
    }
    
    private void processQueryPart(String queryPart, List<String> phrases, List<String> stemmedWords, Map<String, List<Long>> matchingDocuments) {
        boolean isPhraseQuery = false;
        
        // If the entire part is within quotes
        if (queryPart.startsWith("\"") && queryPart.endsWith("\"")) {
            queryPart = queryPart.substring(1, queryPart.length() - 1);
            isPhraseQuery = true;
        }
        
        if (isPhraseQuery) {
            // The entire part is a phrase
            phrases.add(queryPart);
            processPhraseSearch(queryPart, matchingDocuments);
        } else {
            // Extract phrases (text between quotes)
            Matcher matcher = PHRASE_PATTERN.matcher(queryPart);
            
            while (matcher.find()) {
                String phrase = matcher.group(1).trim();
                if (!phrase.isEmpty()) {
                    phrases.add(phrase);
                    processPhraseSearch(phrase, matchingDocuments);
                }
            }
            
            // Process non-phrase parts
            String nonPhraseParts = queryPart.replaceAll("\"[^\"]*\"", " ").trim();
            processRegularWords(nonPhraseParts, stemmedWords, matchingDocuments);
        }
    }
    
    private Set<Long> getUniqueDocIds(Map<String, List<Long>> documentsMap) {
        Set<Long> docIds = new HashSet<>();
        for (List<Long> docs : documentsMap.values()) {
            docIds.addAll(docs);
        }
        return docIds;
    }
    
    private void processRegularWords(String text, List<String> stemmedWords, Map<String, List<Long>> matchingDocuments) {
        // Process regular words
        String[] words = text.toLowerCase().split("\\s+");
        
        // Process words in batches to avoid memory issues
        List<String> processableWords = new ArrayList<>();
        
        // List of words that should never be filtered out even if they're short or stop words
        Set<String> importantTerms = new HashSet<>(Arrays.asList(
            "vs", "war", "israel", "gaza", "iran", "us", "uk", "un", "eu"
        ));
        
        // Log the original words before processing
        logger.debug("Processing query words: {}", Arrays.toString(words));
        
        for (String word : words) {
            word = word.trim();
            if (!word.isEmpty()) {
                // Keep important terms even if they're in the stop words list
                if (importantTerms.contains(word) || (!stopWords.contains(word) && !word.equals("and"))) {
                    String stemmed = stemWord(word);
                    if (!stemmed.isEmpty()) {
                        logger.debug("Adding stemmed word: {} -> {}", word, stemmed);
                        stemmedWords.add(stemmed);
                        processableWords.add(stemmed);
                    } else {
                        // If stemming fails, add the original word
                        logger.debug("Stemming failed, adding original word: {}", word);
                        stemmedWords.add(word);
                        processableWords.add(word);
                    }
                }
            }
        }
        
        logger.info("Processed {} words into {} stemmed terms", words.length, stemmedWords.size());
        
        // Process words in batches of 10
        int batchSize = 10;
        for (int i = 0; i < processableWords.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, processableWords.size());
            List<String> batch = processableWords.subList(i, endIndex);
            processWordBatch(batch, matchingDocuments);
        }
    }
    
    private void processWordBatch(List<String> stemmedBatch, Map<String, List<Long>> matchingDocuments) {
        for (String stemmed : stemmedBatch) {
            try {
                // Find documents containing this word using a more efficient query
                Optional<Word> wordEntity = wordRepository.findByWord(stemmed);
                if (wordEntity.isPresent()) {
                    // Use a more efficient query with pagination
                    List<Long> docIds = jdbcTemplate.query(
                        "SELECT doc_id FROM inverted_index WHERE word_id = ? LIMIT 1000",
                        (rs, rowNum) -> rs.getLong("doc_id"),
                        wordEntity.get().getId()
                    );
                    
                    matchingDocuments.put(stemmed, docIds);
                } else {
                    matchingDocuments.put(stemmed, new ArrayList<>());
                }
            } catch (Exception e) {
                logger.error("Error processing word {}: {}", stemmed, e.getMessage());
                matchingDocuments.put(stemmed, new ArrayList<>());
            }
        }
    }
    
    private void processPhraseSearch(String phrase, Map<String, List<Long>> matchingDocuments) {
        // Process a phrase search
        List<String> words = Arrays.asList(phrase.toLowerCase().split("\\s+"));
        
        // Filter out stop words and stem each word
        List<String> filteredAndStemmed = words.stream()
            .filter(word -> !stopWords.contains(word) && !word.isEmpty())
            .map(this::stemWord)
            .filter(stemmed -> !stemmed.isEmpty())
            .collect(Collectors.toList());
        
        if (filteredAndStemmed.isEmpty()) {
            return;
        }
        
        // Log the phrase being searched
        logger.info("Processing phrase search for: '{}', stemmed as: {}", phrase, filteredAndStemmed);
        
        // Get documents containing all words in the phrase using SQL for efficiency
        Set<Long> docsWithAllWords = null;
        
        try {
            for (String stemmed : filteredAndStemmed) {
                Optional<Word> wordEntity = wordRepository.findByWord(stemmed);
                if (wordEntity.isPresent()) {
                    // Use direct SQL query with higher limit (5000 instead of 1000)
                    List<Long> docIds = jdbcTemplate.query(
                        "SELECT doc_id FROM inverted_index WHERE word_id = ? LIMIT 5000",
                        (rs, rowNum) -> rs.getLong("doc_id"),
                        wordEntity.get().getId()
                    );
                    
                    if (docsWithAllWords == null) {
                        docsWithAllWords = new HashSet<>(docIds);
                    } else {
                        docsWithAllWords.retainAll(docIds);
                        
                        // Early termination if no matches found
                        if (docsWithAllWords.isEmpty()) {
                            break;
                        }
                    }
                } else {
                    // If any word is not found, there are no matching documents
                    docsWithAllWords = new HashSet<>();
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("Error in phrase search for '{}': {}", phrase, e.getMessage());
            docsWithAllWords = new HashSet<>();
        }
        
        if (docsWithAllWords == null || docsWithAllWords.isEmpty()) {
            logger.info("No documents found containing all words in phrase: '{}'", phrase);
            matchingDocuments.put(phrase, new ArrayList<>());
            return;
        }
        
        logger.info("Found {} documents containing all words in phrase: '{}'", docsWithAllWords.size(), phrase);
        
        // Process documents in batches to check if the words appear in sequence
        List<Long> matchingDocs = new ArrayList<>();
        List<Long> docIdsList = new ArrayList<>(docsWithAllWords);
        int batchSize = 20;
        
        for (int i = 0; i < docIdsList.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, docIdsList.size());
            List<Long> batchIds = docIdsList.subList(i, endIndex);
            
            for (Long docId : batchIds) {
                try {
                    if (checkPhraseMatch(docId, filteredAndStemmed)) {
                        matchingDocs.add(docId);
                    }
                } catch (Exception e) {
                    logger.error("Error checking phrase match for doc {}: {}", docId, e.getMessage());
                }
            }
        }
        
        logger.info("Final count: {} documents matching the exact phrase: '{}'", matchingDocs.size(), phrase);
        matchingDocuments.put(phrase, matchingDocs);
    }
    
    private boolean checkPhraseMatch(Long docId, List<String> stemmedPhrase) {
        if (stemmedPhrase.isEmpty()) {
            return false;
        }
        
        // Get the document content to check for exact phrase match first
        Optional<Document> docOpt = documentRepository.findById(docId);
        if (docOpt.isPresent()) {
            Document doc = docOpt.get();
            if (doc.getContent() != null) {
                try {
                    // Parse HTML content
                    org.jsoup.nodes.Document parsedDoc = org.jsoup.Jsoup.parse(doc.getContent());
                    String text = parsedDoc.text().toLowerCase();
                    
                    // Simple direct content check for the exact phrase
                    String searchPhrase = String.join(" ", stemmedPhrase).toLowerCase();
                    if (text.contains(searchPhrase)) {
                        logger.debug("Document {} contains exact phrase match for '{}'", docId, searchPhrase);
                        return true;
                    }
                    
                    // Title check is also important
                    if (doc.getTitle() != null && doc.getTitle().toLowerCase().contains(searchPhrase)) {
                        logger.debug("Document {} contains exact phrase match in title for '{}'", docId, searchPhrase);
                        return true;
                    }
                } catch (Exception e) {
                    logger.debug("Error checking content for phrase match in doc {}: {}", docId, e.getMessage());
                    // Continue with position-based check if content check fails
                }
            }
        }
        
        // Get all occurrences of the first word in the document using direct SQL
        Word firstWordEntity = wordRepository.findByWord(stemmedPhrase.get(0)).orElse(null);
        if (firstWordEntity == null) {
            return false;
        }
        
        try {
            // Get first word positions efficiently with a direct query
            List<Map<String, Object>> firstWordPositions = jdbcTemplate.queryForList(
                "SELECT paragraph_index, word_index FROM word_document_tags " +
                "WHERE word_id = ? AND doc_id = ? LIMIT 100", 
                firstWordEntity.getId(), docId
            );
            
            if (firstWordPositions.isEmpty()) {
                return false;
            }
            
            // Cache word entities to avoid repeated lookups
            Map<String, Long> wordIdCache = new HashMap<>();
            wordIdCache.put(stemmedPhrase.get(0), firstWordEntity.getId());
            
            // Get the remaining word IDs upfront
            for (int i = 1; i < stemmedPhrase.size(); i++) {
                Word nextWordEntity = wordRepository.findByWord(stemmedPhrase.get(i)).orElse(null);
                if (nextWordEntity == null) {
                    return false;
                }
                wordIdCache.put(stemmedPhrase.get(i), nextWordEntity.getId());
            }
            
            // Check each position of the first word
            for (Map<String, Object> firstPos : firstWordPositions) {
                Integer paragraphIdx = (Integer) firstPos.get("paragraph_index");
                Integer wordIdx = (Integer) firstPos.get("word_index");
                
                if (paragraphIdx == null || wordIdx == null) {
                    continue;
                }
                
                boolean allMatch = true;
                
                // Check if all words in the phrase are consecutive
                for (int i = 1; i < stemmedPhrase.size(); i++) {
                    Long nextWordId = wordIdCache.get(stemmedPhrase.get(i));
                    
                    // Check if the next word exists at the exact position
                    int count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM word_document_tags " +
                        "WHERE word_id = ? AND doc_id = ? AND paragraph_index = ? AND word_index = ?", 
                        Integer.class,
                        nextWordId, docId, paragraphIdx, wordIdx + i
                    );
                    
                    if (count == 0) {
                        allMatch = false;
                        break;
                    }
                }
                
                if (allMatch) {
                    logger.debug("Document {} contains consecutive phrase match for position-based check", docId);
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            logger.error("Error in checkPhraseMatch for doc {}: {}", docId, e.getMessage());
            return false;
        }
    }
    
    private List<Map<String, Object>> fetchRegularSearchResults(List<String> stemmedWords, Map<String, List<Long>> matchingDocuments) {
        // For regular search, we need to ensure ALL query terms are present in the document
        Set<Long> finalDocs = null;
        
        // Start with documents matching the first term
        for (String term : stemmedWords) {
            List<Long> docsWithTerm = matchingDocuments.getOrDefault(term, new ArrayList<>());
            
            if (finalDocs == null) {
                // Initialize with first term's documents
                finalDocs = new HashSet<>(docsWithTerm);
            } else {
                // Retain only documents that also contain this term (intersection)
                finalDocs.retainAll(docsWithTerm);
            }
            
            // If no documents match all terms so far, we can stop early
            if (finalDocs.isEmpty()) {
                break;
            }
        }
        
        // If no docs match all terms, use a better fallback strategy
        if (finalDocs == null || finalDocs.isEmpty()) {
            logger.info("No documents matching ALL query terms, using enhanced relevance strategy");
            
            // Step 1: Try to find documents with most of the query terms
            // Count how many terms each document matches
            Map<Long, Integer> docMatchCounts = new HashMap<>();
            
            for (String term : stemmedWords) {
                for (Long docId : matchingDocuments.getOrDefault(term, Collections.emptyList())) {
                    docMatchCounts.put(docId, docMatchCounts.getOrDefault(docId, 0) + 1);
                }
            }
            
            // If no matches at all, just return empty results
            if (docMatchCounts.isEmpty()) {
                return new ArrayList<>();
            }
            
            // Multi-term queries require better term matching
            if (stemmedWords.size() > 1) {
                // Calculate minimum required terms (start with at least 50% of terms)
                int minRequiredTerms = Math.max(1, stemmedWords.size() / 2);
                
                // Try to find docs that match at least the minimum required terms
                Set<Long> highQualityMatches = docMatchCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() >= minRequiredTerms)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
                
                // If we have high-quality matches, use only those
                if (!highQualityMatches.isEmpty()) {
                    finalDocs = highQualityMatches;
                } else {
                    // Fall back to top 20% with best match counts
                    List<Map.Entry<Long, Integer>> sortedDocs = new ArrayList<>(docMatchCounts.entrySet());
                    sortedDocs.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
                    
                    int limit = Math.max(10, sortedDocs.size() / 5); // At least 10 docs or top 20%
                    finalDocs = sortedDocs.stream()
                        .limit(limit)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());
                }
            } else {
                // For single term queries, use all matching docs but limit total
                finalDocs = new HashSet<>(docMatchCounts.keySet());
                if (finalDocs.size() > 100) {
                    // If too many results, just take 100 random ones
                    List<Long> docList = new ArrayList<>(finalDocs);
                    Collections.shuffle(docList);
                    finalDocs = new HashSet<>(docList.subList(0, 100));
                }
            }
        }
        
        logger.info("Final document count after filtering: {}", finalDocs.size());
        return fetchDocumentDetails(stemmedWords, new ArrayList<>(finalDocs));
    }
    
    private List<Map<String, Object>> fetchPhraseSearchResults(String phrase, Map<String, List<Long>> matchingDocuments) {
        List<Long> matchingDocs = matchingDocuments.getOrDefault(phrase, new ArrayList<>());
        
        // For phrase search, we split the phrase into words for snippet generation
        List<String> phraseWords = Arrays.asList(phrase.toLowerCase().split("\\s+"));
        List<String> filteredAndStemmed = phraseWords.stream()
            .filter(word -> !stopWords.contains(word) && !word.isEmpty())
            .map(this::stemWord)
            .filter(stemmed -> !stemmed.isEmpty())
            .collect(Collectors.toList());
        
        return fetchDocumentDetails(filteredAndStemmed, matchingDocs);
    }

    @Autowired
    private SearchConfig searchConfig;

    @Autowired
    private ThreadPoolTaskExecutor searchTaskExecutor;
    
    private List<Map<String, Object>> fetchDocumentDetails(List<String> stemmedWords, List<Long> docIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        // Process documents in batches to avoid memory issues
        int batchSize = searchConfig.getMaxBatchSize();
        List<Long> limitedDocIds = docIds.size() > 1000 ? docIds.subList(0, 1000) : docIds;
        
        try {
            // Process documents in parallel with batching
            List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
            
            for (int i = 0; i < limitedDocIds.size(); i += batchSize) {
                final int startIdx = i;
                final int endIdx = Math.min(i + batchSize, limitedDocIds.size());
                
                CompletableFuture<List<Map<String, Object>>> future = CompletableFuture.supplyAsync(() -> {
                    List<Map<String, Object>> batchResults = new ArrayList<>();
                    List<Long> batch = limitedDocIds.subList(startIdx, endIdx);
                    
                    for (Long docId : batch) {
                        try {
                            // Use efficient SQL query instead of loading full document
                            String sql = "SELECT id, url, title, content FROM documents WHERE id = ?";
                            Map<String, Object> docData = jdbcTemplate.queryForMap(sql, docId);
                            
                            if (docData != null) {
                                Map<String, Object> result = new HashMap<>();
                                result.put("id", docData.get("id"));
                                result.put("url", docData.get("url"));
                                result.put("title", docData.get("title"));
                                
                                // Get the document content for proximity search
                                String content = (String)docData.get("content");
                                
                                // Calculate relevance score using optimized method
                                double score = calculateRelevanceScoreOptimized(docId, stemmedWords);
                                
                                if (content != null) {
                                    // Check for terms proximity - when terms appear close together in content
                                    double proximityScore = calculateTermProximity(content, stemmedWords);
                                    
                                    // Add proximity score to the final score
                                    score += proximityScore;
                                }
                                
                                result.put("score", score);
                                
                                // Generate a snippet highlighting the query terms
                                String snippet = generateSnippet((String)docData.get("content"), stemmedWords);
                                result.put("snippet", snippet);
                                
                                batchResults.add(result);
                            }
                        } catch (Exception e) {
                            logger.error("Error processing document {}: {}", docId, e.getMessage());
                        }
                    }
                    
                    return batchResults;
                }, searchTaskExecutor);
                
                futures.add(future);
            }
            
            // Combine results from all futures
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                try {
                    results.addAll(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    logger.error("Error waiting for document processing: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error in parallel document processing: {}", e.getMessage());
        }
        
        // Sort results by score, descending
        results.sort((r1, r2) -> Double.compare((double)r2.get("score"), (double)r1.get("score")));
        
        return results;
    }
    
    // Optimized version for better performance
    private double calculateRelevanceScoreOptimized(Long docId, List<String> stemmedWords) {
        double score = 0.0;
        
        try {
            // Skip empty queries
            if (stemmedWords.isEmpty()) {
                return 0.0;
            }
            
            // Get the document content upfront
            Optional<Document> docOpt = documentRepository.findById(docId);
            if (!docOpt.isPresent()) {
                return 0.0; // Document doesn't exist
            }
            
            Document doc = docOpt.get();
            String title = doc.getTitle() != null ? doc.getTitle().toLowerCase() : "";
            String url = doc.getUrl() != null ? doc.getUrl().toLowerCase() : "";
            
            // Get document length for TF normalization
            String content = doc.getContent() != null ? doc.getContent() : "";
            org.jsoup.nodes.Document parsedDoc = org.jsoup.Jsoup.parse(content);
            String parsedText = parsedDoc.text().toLowerCase();
            int documentLength = parsedText.split("\\s+").length;
            
            // If document is too short, it might be suspicious
            if (documentLength < 50) {
                return 0.01; // Very low score for extremely short documents
            }
            
            // Create query signature for topic detection
            String querySignature = String.join(" ", stemmedWords).toLowerCase();
            
            // Direct relevance boosting - highest priority
            // This should override all other scoring for exact matches
            
            // If document has the exact query in the title, give it an extremely high score
            if (title.contains(querySignature)) {
                return 1000.0; // Maximum priority
            }
            
            // If the document has the query terms in the URL, very high priority
            if (stemmedWords.size() > 1) {
                boolean allTermsInUrl = true;
                for (String term : stemmedWords) {
                    if (!url.contains(term.toLowerCase())) {
                        allTermsInUrl = false;
                        break;
                    }
                }
                
                if (allTermsInUrl) {
                    return 500.0; // Very high priority
                }
            }
            
            // Check if all query terms appear in the title (not necessarily as a phrase)
            boolean allTermsInTitle = true;
            for (String term : stemmedWords) {
                if (!title.contains(term.toLowerCase())) {
                    allTermsInTitle = false;
                    break;
                }
            }
            
            if (allTermsInTitle) {
                return 300.0; // High priority
            }
            
            // Calculate TF-IDF for each term in the query
            double tfIdfSum = 0.0;
            long totalDocs = documentRepository.count(); // Total documents in corpus
            
            // Track how many terms are actually found in the document
            int termsFoundInDoc = 0;
            
            for (String term : stemmedWords) {
                // Skip empty terms
                if (term == null || term.isEmpty()) {
                    continue;
                }
                
                // Find word entity
                Optional<Word> wordEntity = wordRepository.findByWord(term);
                if (!wordEntity.isPresent()) {
                    continue; // Term not in index
                }
                
                // Get term frequency (TF) for this document
                Integer rawFrequency = null;
                try {
                    rawFrequency = jdbcTemplate.queryForObject(
                        "SELECT frequency FROM inverted_index WHERE word_id = ? AND doc_id = ?",
                        Integer.class, wordEntity.get().getId(), docId);
                } catch (Exception e) {
                    rawFrequency = 0;
                }
                
                if (rawFrequency == null || rawFrequency == 0) {
                    continue; // Term not in document
                }
                
                // Term was found in document
                termsFoundInDoc++;
                
                // Calculate normalized TF (term frequency / document length)
                double tf = (double) rawFrequency / Math.max(1, documentLength);
                
                // Check for term frequency spam - suspicious if term is >10% of document
                if (tf > 0.1) {
                    tf = 0.1; // Cap TF at 10% to prevent spam
                }
                
                // Get document frequency (how many documents contain this term)
                Long docsWithTerm = null;
                try {
                    docsWithTerm = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM inverted_index WHERE word_id = ?",
                        Long.class, wordEntity.get().getId());
                } catch (Exception e) {
                    docsWithTerm = 1L; // Default to 1 to avoid division by zero
                }
                
                if (docsWithTerm == null || docsWithTerm == 0) {
                    docsWithTerm = 1L;
                }
                
                // Calculate IDF (logarithm of total docs / docs with term)
                double idf = Math.log10((double) totalDocs / docsWithTerm);
                
                // Calculate TF-IDF for this term
                double tfIdf = tf * idf;
                
                // Add term's TF-IDF to sum
                tfIdfSum += tfIdf;
                
                // Apply field weightings (title and URL are more important)
                if (title.contains(term.toLowerCase())) {
                    // Title matches are very important (3x boost)
                    tfIdfSum += tfIdf * 3.0;
                }
                
                if (url.contains(term.toLowerCase())) {
                    // URL matches are important (2x boost)
                    tfIdfSum += tfIdf * 2.0;
                }
            }
            
            // Penalize documents missing critical query terms
            // If half or more terms are missing, severely reduce score
            if (termsFoundInDoc < stemmedWords.size() / 2) {
                tfIdfSum *= 0.1; // 90% reduction for documents missing too many terms
            }
            
            // Check for phrase matching (better relevance)
            if (stemmedWords.size() > 1) {
                String phrase = String.join(" ", stemmedWords).toLowerCase();
                if (parsedText.contains(phrase)) {
                    // Exact phrase match is a strong signal (3x boost)
                    tfIdfSum *= 3.0;
                }
                
                // Check title for phrase
                if (title.contains(phrase)) {
                    // Phrase in title is extremely valuable (3x boost)
                    tfIdfSum *= 3.0;
                }
            }
            
            // Final score is the sum of TF-IDF values for all terms
            score = tfIdfSum;
            
        } catch (Exception e) {
            logger.error("Error calculating relevance score for doc {}: {}", docId, e.getMessage());
        }
        
        return score;
    }
    
    /**
     * Extracts the first meaningful paragraph from text
     */
    private String getFirstParagraph(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        // Split by paragraph breaks and find first non-empty paragraph
        String[] paragraphs = text.split("\n\n|\r\n\r\n");
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.length() > 50) { // Meaningful paragraph should be at least 50 chars
                return trimmed;
            }
        }
        
        // Fall back to the first 200 characters if no good paragraph is found
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
    
    /**
     * Count occurrences of a term in text
     */
    private int countOccurrences(String text, String term) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) != -1) {
            count++;
            index += term.length();
        }
        return count;
    }
    
    private String generateSnippet(String content, List<String> stemmedWords) {
        try {
            // Parse HTML content
            org.jsoup.nodes.Document parsedDoc = org.jsoup.Jsoup.parse(content);
            String text = parsedDoc.text();
            
            if (text == null || text.isEmpty()) {
                return "No content available";
            }
            
            // Find the best paragraph containing the query words
            String[] paragraphs = text.split("\\. ");
            
            if (paragraphs.length == 0) {
                return "No content available";
            }
            
            // Find paragraph with most query term matches
            int bestMatchCount = 0;
            String bestParagraph = "";
            
            // Create a search phrase from all terms
            String searchPhrase = String.join(" ", stemmedWords).toLowerCase();
            
            // First check for exact phrase match
            for (String paragraph : paragraphs) {
                if (paragraph != null && paragraph.length() >= 20) {
                    String lowerParagraph = paragraph.toLowerCase();
                    if (lowerParagraph.contains(searchPhrase)) {
                        // Found exact phrase match - prioritize this paragraph
                        bestParagraph = paragraph;
                        bestMatchCount = stemmedWords.size() + 1; // Extra bonus to ensure this is picked
                        break;
                    }
                }
            }
            
            // If no exact phrase match, look for paragraph with most term matches
            if (bestMatchCount == 0) {
                for (String paragraph : paragraphs) {
                    if (paragraph != null && paragraph.length() >= 20) {
                        String lowerParagraph = paragraph.toLowerCase();
                        
                        int matchCount = 0;
                        for (String word : stemmedWords) {
                            if (word != null && !word.isEmpty() && lowerParagraph.contains(word.toLowerCase())) {
                                matchCount++;
                            }
                        }
                        
                        if (matchCount > bestMatchCount) {
                            bestMatchCount = matchCount;
                            bestParagraph = paragraph;
                        }
                    }
                }
            }
            
            // If still no good match, use the first substantial paragraph
            if (bestParagraph.isEmpty()) {
                for (String paragraph : paragraphs) {
                    if (paragraph != null && paragraph.length() >= 50) {
                        bestParagraph = paragraph;
                        break;
                    }
                }
                
                // If still empty, just use the first paragraph
                if (bestParagraph.isEmpty() && paragraphs.length > 0 && paragraphs[0] != null) {
                    bestParagraph = paragraphs[0];
                }
            }
            
            // Trim the paragraph if it's too long
            if (bestParagraph.length() > 300) {
                bestParagraph = bestParagraph.substring(0, 297) + "...";
            }
            
            // Highlight the query terms in the snippet (bold them)
            String snippet = bestParagraph;
            for (String word : stemmedWords) {
                if (word != null && !word.isEmpty()) {
                    try {
                        // Use case-insensitive replacement with regex, but handle special characters safely
                        String safeWord = Pattern.quote(word);
                        String regex = "(?i)\\b" + safeWord + "\\b";
                        snippet = snippet.replaceAll(regex, "<strong>$0</strong>");
                    } catch (Exception e) {
                        // If regex fails, try simple replacement
                        logger.debug("Regex replacement failed for term {}, using simple replace", word);
                        String lowerSnippet = snippet.toLowerCase();
                        String lowerWord = word.toLowerCase();
                        
                        int index = lowerSnippet.indexOf(lowerWord);
                        while (index >= 0) {
                            String original = snippet.substring(index, index + word.length());
                            snippet = snippet.substring(0, index) + 
                                    "<strong>" + original + "</strong>" + 
                                    snippet.substring(index + word.length());
                            
                            // Move past this replacement
                            index = lowerSnippet.indexOf(lowerWord, index + word.length() + 17); // +17 for the added HTML tags
                        }
                    }
                }
            }
            
            return snippet;
        } catch (Exception e) {
            logger.error("Error generating snippet: {}", e.getMessage());
            return "Content preview unavailable";
        }
    }
    
    private double calculateRelevanceScore(Long docId, List<String> stemmedWords) {
        double score = 0.0;
        
        // Get total number of documents for IDF calculation
        long totalDocs = documentRepository.count();
        
        for (String word : stemmedWords) {
            Optional<Word> wordEntity = wordRepository.findByWord(word);
            if (wordEntity.isPresent()) {
                try {
                    // Calculate TF (term frequency) using direct SQL query
                    Integer termFreq = jdbcTemplate.queryForObject(
                        "SELECT frequency FROM inverted_index WHERE word_id = ? AND doc_id = ?",
                        Integer.class, wordEntity.get().getId(), docId);
                    
                    if (termFreq != null) {
                        // Calculate IDF (inverse document frequency) using direct SQL query
                        Long docsWithTerm = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM inverted_index WHERE word_id = ?",
                            Long.class, wordEntity.get().getId());
                        double idf = Math.log10((double)totalDocs / (docsWithTerm + 1));
                        
                        // TF-IDF score
                        double tfIdf = termFreq * idf;
                        
                        // Add to total score
                        score += tfIdf;
                        
                        // Boost score for words in important tags (title, h1, etc.)
                        try {
                            // Use direct SQL query to get tags for this word and document
                            List<Map<String, Object>> wordDocTagList = jdbcTemplate.queryForList(
                                "SELECT * FROM word_document_tags WHERE word_id = ? AND doc_id = ?",
                                wordEntity.get().getId(), docId
                            );
                            
                            for (Map<String, Object> tagData : wordDocTagList) {
                                String tag = (String) tagData.get("tag");
                                if (tag.equals("title")) {
                                    score += tfIdf * 3;
                                } else if (tag.equals("h1")) {
                                    score += tfIdf * 2;
                                } else if (tag.startsWith("h")) {
                                    score += tfIdf * 1.5;
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Error getting tags for word {} in doc {}", word, docId);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Error calculating term frequency for word {} in doc {}", word, docId);
                }
            }
        }
        
        return score;
    }

    // Replace Snowball stemmer with Porter stemmer
    private String stemWord(String word) {
        if (word == null || word.isEmpty()) {
        return word;
        }
        
        // Use the Porter stemmer directly
        return porterStemmer.stem(word.toLowerCase());
    }

    private static HashSet<String> getStopWords() {
        HashSet<String> stopWordsSet = new HashSet<>();
        try {
            File stopFile = new File("StopWords.txt").getAbsoluteFile();
            if (!stopFile.exists()) {
                logger.warn("StopWords.txt not found, using default stop words");
                stopWordsSet.addAll(Arrays.asList("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "he", "in", "is", "it", "its", "of", "on", "that", "the", "this", "to", "was", "were", "will", "with"));
                return stopWordsSet;
            }
            Scanner reader = new Scanner(stopFile);
            while (reader.hasNextLine()) {
                String word = reader.nextLine();
                stopWordsSet.add(word);
            }
            reader.close();
        } catch (Exception e) {
            logger.error("An exception occurred while reading the stop words!", e);
        }
        return stopWordsSet;
    }

    /**
     * Checks if a document contains a given term or phrase, either in its content or metadata
     * Used primarily for NOT operator filtering
     */
    private boolean documentContainsTermOrPhrase(Document document, String term) {
        // Check the title
        if (document.getTitle() != null && 
            document.getTitle().toLowerCase().contains(term.toLowerCase())) {
            return true;
        }
        
        // Check the URL
        if (document.getUrl() != null && 
            document.getUrl().toLowerCase().contains(term.toLowerCase())) {
            return true;
        }
        
        // Check the content (most important)
        if (document.getContent() != null) {
            String content = document.getContent().toLowerCase();
            
            // Check if the content contains the term directly
            if (content.contains(term.toLowerCase())) {
                return true;
            }
            
            // For multi-word terms, check with stemming
            if (term.contains(" ")) {
                List<String> termWords = Arrays.asList(term.toLowerCase().split("\\s+"));
                List<String> stemmedTermWords = termWords.stream()
                    .filter(w -> !stopWords.contains(w) && !w.isEmpty())
                    .map(this::stemWord)
                    .filter(stemmed -> !stemmed.isEmpty())
                    .collect(Collectors.toList());
                    
                if (stemmedTermWords.isEmpty()) {
                    return false;
                }
                
                // Parse content into words
                String[] contentWords = Jsoup.parse(content).text().toLowerCase().split("\\s+");
                List<String> stemmedContentWords = Arrays.stream(contentWords)
                    .map(w -> stemWord(w.toLowerCase()))
                    .collect(Collectors.toList());
                    
                // Look for the phrase sequence in the content
                for (int i = 0; i <= stemmedContentWords.size() - stemmedTermWords.size(); i++) {
                    boolean found = true;
                    for (int j = 0; j < stemmedTermWords.size(); j++) {
                        if (!stemmedContentWords.get(i + j).equals(stemmedTermWords.get(j))) {
                            found = false;
                            break;
                        }
                    }
                    if (found) {
                        return true;
                    }
                }
            } else {
                // For single word terms, check if any stemmed word in content matches
                String stemmedTerm = stemWord(term.toLowerCase());
                if (stemmedTerm.isEmpty()) {
                    return false;
                }
                
                String[] contentWords = Jsoup.parse(content).text().toLowerCase().split("\\s+");
                for (String contentWord : contentWords) {
                    String stemmedContentWord = stemWord(contentWord.toLowerCase());
                    if (stemmedContentWord.equals(stemmedTerm)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    private String extractDomainFromUrl(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            return host.replaceFirst("^www\\.", "").replaceFirst("\\.[^\\.]+$", "");
        } catch (Exception e) {
            logger.error("Error extracting domain from URL: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Calculate term proximity for terms not appearing as an exact phrase
     * Rewards documents where query terms appear close to each other
     */
    private double calculateTermProximity(String text, List<String> terms) {
        double proximityScore = 0.0;
        
        try {
            // Split text into words
            String[] words = text.split("\\s+");
            
            // Find positions of each term
            Map<String, List<Integer>> termPositions = new HashMap<>();
            
            for (String term : terms) {
                termPositions.put(term.toLowerCase(), new ArrayList<>());
            }
            
            // Scan document for term positions
            for (int i = 0; i < words.length; i++) {
                String word = words[i].toLowerCase();
                for (String term : terms) {
                    if (word.contains(term.toLowerCase())) {
                        termPositions.get(term.toLowerCase()).add(i);
                    }
                }
            }
            
            // Skip terms that don't appear
            List<String> appearingTerms = terms.stream()
                .filter(term -> !termPositions.get(term.toLowerCase()).isEmpty())
                .collect(Collectors.toList());
            
            if (appearingTerms.size() < 2) {
                return 0.0; // Need at least 2 terms for proximity
            }
            
            // Find closest positions between terms
            int minDistance = Integer.MAX_VALUE;
            
            // Compare each term with each other term
            for (int i = 0; i < appearingTerms.size(); i++) {
                for (int j = i + 1; j < appearingTerms.size(); j++) {
                    List<Integer> positions1 = termPositions.get(appearingTerms.get(i).toLowerCase());
                    List<Integer> positions2 = termPositions.get(appearingTerms.get(j).toLowerCase());
                    
                    // Find minimum distance between positions
                    for (Integer pos1 : positions1) {
                        for (Integer pos2 : positions2) {
                            int distance = Math.abs(pos1 - pos2);
                            minDistance = Math.min(minDistance, distance);
                        }
                    }
                }
            }
            
            // Calculate proximity score based on distance
            if (minDistance < Integer.MAX_VALUE) {
                if (minDistance <= 3) {
                    // Terms very close together (within 3 words)
                    proximityScore = 2.0;
                } else if (minDistance <= 10) {
                    // Terms somewhat close (within 10 words)
                    proximityScore = 1.0;
                } else if (minDistance <= 50) {
                    // Terms in same general area
                    proximityScore = 0.5;
                }
            }
            
        } catch (Exception e) {
            logger.error("Error calculating term proximity: {}", e.getMessage());
        }
        
        return proximityScore;
    }
    
    /**
     * Get semantic relations for terms (simplified synonym matching)
     * In a real system, this would use a proper thesaurus or word embedding model
     */
    private Map<String, List<String>> getSemanticRelations() {
        // Simple hardcoded semantic relations - in a real system would use an NLP model
        Map<String, List<String>> relations = new HashMap<>();
        
        // Political terms
        relations.put("election", Arrays.asList("vote", "ballot", "poll", "voting", "elect", "democratic"));
        relations.put("vote", Arrays.asList("ballot", "election", "poll", "voting", "elect"));
        relations.put("politics", Arrays.asList("political", "government", "governance", "policy"));
        relations.put("president", Arrays.asList("presidential", "administration", "leader", "elect", "head"));
        
        // Countries and regions
        relations.put("egypt", Arrays.asList("egyptian", "cairo", "africa", "north africa", "middle east"));
        relations.put("us", Arrays.asList("usa", "united states", "america", "american"));
        relations.put("uk", Arrays.asList("united kingdom", "britain", "british", "london"));
        
        // News terms
        relations.put("news", Arrays.asList("report", "media", "press", "article"));
        relations.put("war", Arrays.asList("conflict", "battle", "fighting", "combat", "warfare"));
        relations.put("peace", Arrays.asList("ceasefire", "truce", "agreement", "treaty"));
        
        return relations;
    }

    /**
     * Filter out low-quality search results that shouldn't be shown to users
     */
    private List<Map<String, Object>> filterLowQualityResults(
            List<Map<String, Object>> results, 
            List<String> stemmedWords,
            List<String> phrases) {
            
        // Simple filtering - just remove obvious product pages for non-product queries
        boolean isProductQuery = false;
        Set<String> productTerms = Set.of("buy", "price", "shop", "store", "product", "purchase");
        
        for (String term : stemmedWords) {
            if (productTerms.contains(term.toLowerCase())) {
                isProductQuery = true;
                break;
            }
        }
        
        // If it's not a product query, filter out e-commerce results
        if (!isProductQuery) {
            return results.stream()
                .filter(result -> {
                    // Get the URL and title
                    String url = (String) result.get("url");
                    String title = (String) result.get("title");
                    
                    if (url == null || title == null) {
                        return false;
                    }
                    
                    // Basic product page detection
                    boolean isBasicProductPage = 
                        url.contains("/product/") || 
                        url.contains("/shop/product") ||
                        url.contains("product-") ||
                        url.contains("apple.com/shop");
                    
                    return !isBasicProductPage;
                })
                .collect(Collectors.toList());
        }
        
        // For other queries, keep all results
        return results;
    }
    
    /**
     * Generate suggested related queries based on search results
     */
    private List<String> generateSuggestedQueries(String originalQuery, List<Map<String, Object>> results) {
        Set<String> suggestions = new HashSet<>();
        
        // Extract words from titles of top results
        int maxResultsToAnalyze = Math.min(5, results.size());
        Set<String> commonTerms = new HashSet<>();
        
        for (int i = 0; i < maxResultsToAnalyze; i++) {
            String title = (String) results.get(i).get("title");
            if (title == null) continue;
            
            // Extract significant terms from title
            String[] titleWords = title.toLowerCase().split("\\s+");
            for (String word : titleWords) {
                if (word.length() > 3 && !stopWords.contains(word)) {
                    commonTerms.add(word);
                }
            }
        }
        
        // Build suggested queries by combining original query with common terms
        String query = originalQuery.toLowerCase();
        for (String term : commonTerms) {
            // Skip terms already in the query
            if (query.contains(term)) continue;
            
            // Add new suggestions
            suggestions.add(query + " " + term);
            
            // If query has spaces, also suggest with the first word replaced
            if (query.contains(" ")) {
                String[] queryParts = query.split("\\s+", 2);
                if (queryParts.length > 1) {
                    suggestions.add(term + " " + queryParts[1]);
                }
            }
        }
        
        // Convert to list and limit to top 5
        return suggestions.stream()
                .limit(5)
                .collect(Collectors.toList());
    }
} 