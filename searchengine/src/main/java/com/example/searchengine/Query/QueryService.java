package com.example.searchengine.Query;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Indexer.Entities.Word;
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
            // Check if the query contains operators (AND, OR, NOT)
            Matcher operatorMatcher = OPERATOR_PATTERN.matcher(query);
            if (operatorMatcher.find()) {
                // Ensure the query has proper quoted phrases on both sides of the operator
                String operator = operatorMatcher.group(1).toUpperCase();
                String[] parts = OPERATOR_PATTERN.split(query);

                if (parts.length == 2) {
                    String leftPart = parts[0].trim();
                    String rightPart = parts[1].trim();

                    // Check if both parts have quoted phrases
                    boolean leftHasQuotedPhrase = leftPart.startsWith("\"") && leftPart.endsWith("\"");
                    boolean rightHasQuotedPhrase = rightPart.startsWith("\"") && rightPart.endsWith("\"");

                    if (leftHasQuotedPhrase && rightHasQuotedPhrase) {
                        logger.info("Processing as complex phrase query with operator: {}", operator);
                        return processComplexPhraseQuery(query, result);
                    } else {
                        logger.warn("Invalid query: Operators require both terms to be phrased.");
                        result.setErrorMessage("Operators (AND, OR, NOT) require both terms to be enclosed in quotes.");
                        return result;
                    }
                }
            }

            // Handle simple queries or phrase queries
            boolean isPhraseQuery = query.startsWith("\"") && query.endsWith("\"");
            if (isPhraseQuery) {
                query = query.substring(1, query.length() - 1);
                result.setPhraseQuery(true);
                logger.info("Processing as exact phrase query: '{}'", query);
                return processFullPhraseQuery(query, result);
            }

            // Process regular words
            List<String> phrases = new ArrayList<>();
            List<String> stemmedWords = new ArrayList<>();
            Map<String, List<Long>> matchingDocuments = new HashMap<>();
            processRegularWords(query, stemmedWords, matchingDocuments);

            result.setStemmedWords(stemmedWords);
            result.setMatchingDocuments(matchingDocuments);

            // Fetch results
            List<Map<String, Object>> searchResults = fetchRegularSearchResults(stemmedWords, matchingDocuments);
            result.setResults(searchResults);

        } catch (Exception e) {
            logger.error("Error processing query: {}", query, e);
            result.setErrorMessage("An error occurred while processing your query.");
        }

        return result;
    }

    /**
     * Process a full phrase query using the optimized phrase searching implementation
     */
    private QueryResult processFullPhraseQuery(String phrase, QueryResult result) {
        logger.info("Using optimized phrase search for: '{}'", phrase);
        
        // Create instance of PhraseSearching with all necessary dependencies
        PhraseSearching phraseSearcher = new PhraseSearching(
            phrase,
            jdbcTemplate,
            wordRepository,
            documentRepository,
            stopWords
        );
        
        // Get the sorted results
        List<Long> matchingDocIds = phraseSearcher.getSortedDocumentIds();
        Map<Long, Double> docScores = phraseSearcher.getResults();
        
        // Add the phrase to the result
        List<String> phrases = new ArrayList<>();
        phrases.add(phrase);
        result.setPhrases(phrases);
        
        // Create mapping for matching documents
        Map<String, List<Long>> matchingDocuments = new HashMap<>();
        matchingDocuments.put(phrase, matchingDocIds);
        result.setMatchingDocuments(matchingDocuments);
        
        // Create stemmed words for snippet generation
        List<String> stemmedWords = Arrays.stream(phrase.split("\\s+"))
            .map(this::stemWord)
            .filter(w -> !w.isEmpty())
            .collect(Collectors.toList());
        result.setStemmedWords(stemmedWords);
        
        // Fetch full document details with snippets
        List<Map<String, Object>> searchResults = fetchDocumentDetails(stemmedWords, matchingDocIds);
        
        // Add scores from phrase searcher
        for (Map<String, Object> doc : searchResults) {
            Long docId = ((Number) doc.get("id")).longValue();
            doc.put("score", docScores.getOrDefault(docId, 0.0));
        }
        
        // Sort by relevance score
        searchResults.sort((r1, r2) -> 
            Double.compare(
                ((Number) r2.get("score")).doubleValue(),
                ((Number) r1.get("score")).doubleValue()
            )
        );
        
        // Apply final filtering
        if (!searchResults.isEmpty()) {
            searchResults = filterLowQualityResults(searchResults, stemmedWords, phrases);
            result.setSuggestedQueries(generateSuggestedQueries(phrase, searchResults));
        }
        
        result.setResults(searchResults);
        logger.info("Optimized phrase search completed with {} results", searchResults.size());
        
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
        // Extract the phrase without quotes
        String cleanPhrase = phrase.substring(1, phrase.length() - 1).trim().toLowerCase();
        
        // Skip empty phrases
        if (cleanPhrase.isEmpty()) {
            return;
        }
        
        logger.info("Processing phrase search for: '{}'", cleanPhrase);
        
        // Split the phrase into words and stem them
        List<String> stemmedPhrase = Arrays.stream(cleanPhrase.split("\\s+"))
                                         .map(this::stemWord)
                                         .filter(word -> !stopWords.contains(word))
                                         .collect(Collectors.toList());
        
        // Skip phrases with only stop words
        if (stemmedPhrase.isEmpty()) {
            return;
        }
        
        // Use first word in phrase to narrow down document set
        String firstWord = stemmedPhrase.get(0);
        List<Long> docsWithFirstWord = new ArrayList<>();
        
        // Look for the first word in the index
        try {
            // Get documents containing the first meaningful word (limit to 200 for performance)
            String query = "SELECT DISTINCT doc_id FROM inverted_index " +
                          "WHERE word_id IN (SELECT id FROM words WHERE word = ?) " +
                          "LIMIT 200";
            docsWithFirstWord = jdbcTemplate.queryForList(query, Long.class, firstWord);
            
            logger.info("Found {} documents containing first word '{}' of phrase", 
                        docsWithFirstWord.size(), firstWord);
        } catch (Exception e) {
            logger.error("Error querying for documents with first word: {}", e.getMessage());
        }
        
        List<Long> matchingDocs = new ArrayList<>();
        int matchCount = 0;
        final int MAX_PHRASE_RESULTS = 50; // Limit the number of phrase matches
        
        // Check each document for the exact phrase
        for (Long docId : docsWithFirstWord) {
            if (matchCount >= MAX_PHRASE_RESULTS) {
                logger.info("Reached maximum phrase match count ({}), stopping search", MAX_PHRASE_RESULTS);
                break;
            }
            
            boolean hasPhrase = checkPhraseMatch(docId, stemmedPhrase);
            if (hasPhrase) {
                matchingDocs.add(docId);
                matchCount++;
                
                // Limit logging verbosity
                if (matchCount % 10 == 0 || matchCount <= 3) {
                    logger.info("Found exact phrase match in document {}", docId);
                }
            }
        }
        
        logger.info("Found {} documents matching phrase: '{}'", matchingDocs.size(), cleanPhrase);
        matchingDocuments.put(phrase, matchingDocs);
    }

    private boolean checkPhraseMatch(Long docId, List<String> stemmedPhrase) {
        try {
            // Get document content
            Optional<Document> docOpt = documentRepository.findById(docId);
            if (!docOpt.isPresent() || docOpt.get().getContent() == null) {
                return false;
            }
            
            Document doc = docOpt.get();
            
            // Check title first (faster than content)
            if (doc.getTitle() != null) {
                String titleText = Jsoup.parse(doc.getTitle()).text().toLowerCase();
                String phraseText = String.join(" ", stemmedPhrase);
                if (titleText.contains(phraseText)) {
                    return true;
                }
            }
            
            // Parse and clean the document content
            String content = doc.getContent();
            String cleanContent = Jsoup.parse(content).text().toLowerCase();
            
            // Quick check for the whole phrase (faster than position checking)
            String phraseText = String.join(" ", stemmedPhrase);
            if (cleanContent.contains(phraseText)) {
                return true;
            }
            
            // For short phrases (2-3 words), the above check is sufficient
            // Only do position checking for longer phrases if necessary
            if (stemmedPhrase.size() <= 3 || cleanContent.length() < 1000) {
                return false;
            }
            
            // More thorough check only needed for complex cases
            // Convert content to words and check for sequence
            List<String> contentWords = Arrays.asList(cleanContent.split("\\s+"));
            List<String> stemmedContentWords = contentWords.stream()
                .map(this::stemWord)
                .collect(Collectors.toList());
            
            // Look for the sequence of words in the stemmed content
            for (int i = 0; i <= stemmedContentWords.size() - stemmedPhrase.size(); i++) {
                boolean found = true;
                for (int j = 0; j < stemmedPhrase.size(); j++) {
                    if (!stemmedContentWords.get(i + j).equals(stemmedPhrase.get(j))) {
                        found = false;
                        break;
                    }
                }
                if (found) {
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            logger.warn("Error checking phrase match for document {}: {}", docId, e.getMessage());
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
                if (finalDocs.size() > 1000) {
                    // If too many results, just take 100 random ones
                    List<Long> docList = new ArrayList<>(finalDocs);
                    Collections.shuffle(docList);
                    finalDocs = new HashSet<>(docList.subList(0, 1000));
                }
            }
        }
        
        logger.info("Final document count after filtering: {}", finalDocs.size());
        return fetchDocumentDetails(stemmedWords, new ArrayList<>(finalDocs));
    }
    
    private List<Map<String, Object>> fetchPhraseSearchResults(String phrase, Map<String, List<Long>> matchingDocuments) {
        List<Long> matchingDocs = matchingDocuments.getOrDefault(phrase, new ArrayList<>());
        
        // Extract the phrase without quotes
        String cleanPhrase = phrase;
        if (phrase.startsWith("\"") && phrase.endsWith("\"")) {
            cleanPhrase = phrase.substring(1, phrase.length() - 1).trim();
        }
        
        logger.info("Fetching details for {} documents matching phrase '{}'", matchingDocs.size(), cleanPhrase);
        
        // For phrase search, we keep the complete phrase for snippet generation
        // but also split it for the scoring algorithm
        List<String> phraseWords = Arrays.asList(cleanPhrase.toLowerCase().split("\\s+"));
        List<String> filteredAndStemmed = phraseWords.stream()
            .filter(word -> !stopWords.contains(word) && !word.isEmpty())
            .map(this::stemWord)
            .filter(stemmed -> !stemmed.isEmpty())
            .collect(Collectors.toList());
        
        // Store the original phrase as metadata for snippet generation
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("isExactPhrase", true);
        metadata.put("originalPhrase", cleanPhrase);
        
        // Create results with proper metadata for snippet generation
        List<Map<String, Object>> results = fetchDocumentDetails(filteredAndStemmed, matchingDocs);
        
        // Add phrase metadata to each result
        for (Map<String, Object> result : results) {
            result.put("isExactPhrase", true);
            result.put("queryPhrase", cleanPhrase);
        }
        
        return results;
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
                                String snippet;
                                
                                // If we're looking for an exact phrase (stemmedWords.size() > 1), 
                                // treat it as a phrase search for snippet generation
                                boolean isExactPhraseSearch = stemmedWords.size() > 1;
                                
                                // Generate appropriate snippet based on search type
                                if (isExactPhraseSearch) {
                                    // For phrase searches, join the words back into a phrase for better matching
                                    String searchPhrase = String.join(" ", stemmedWords);
                                    
                                    // Check if we're processing a phrase and need to highlight it exactly
                                    if (docData.get("content") != null) {
                                        snippet = generatePhraseSnippet((String)docData.get("content"), searchPhrase);
                                    } else {
                                        snippet = "No content available";
                                    }
                                } else {
                                    // For regular searches, use the normal snippet generator
                                    snippet = generateSnippet((String)docData.get("content"), stemmedWords);
                                }
                                
                                result.put("snippet", snippet);
                                
                                // Generate a clean, meaningful description for search results
                                result.put("description", generateSearchResultDescription(docData));
                                
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
    
    /**
     * Special snippet generator specifically for phrase searches
     * This ensures the exact phrase is highlighted in context
     */
    private String generatePhraseSnippet(String content, String phrase) {
        try {
            if (content == null || content.isEmpty() || phrase == null || phrase.isEmpty()) {
                return "No content available";
            }
            
            // Parse HTML content
            org.jsoup.nodes.Document parsedDoc = org.jsoup.Jsoup.parse(content);
            String text = parsedDoc.text();
            
            // Lowercase for case-insensitive matching
            String lowerText = text.toLowerCase();
            String lowerPhrase = phrase.toLowerCase();
            
            // Find the position of the phrase in the text
            int phrasePos = lowerText.indexOf(lowerPhrase);
            if (phrasePos == -1) {
                // If phrase not found, fall back to regular snippet
                return generateSnippet(content, Arrays.asList(phrase.split("\\s+")));
            }
            
            // Find a good context window around the phrase
            int contextStart = Math.max(0, phrasePos - 100);
            int contextEnd = Math.min(text.length(), phrasePos + phrase.length() + 100);
            
            // Try to start at a word boundary
            if (contextStart > 0) {
                while (contextStart > 0 && Character.isLetterOrDigit(text.charAt(contextStart))) {
                    contextStart--;
                }
                // Skip any non-word characters at the beginning
                while (contextStart < phrasePos && !Character.isLetterOrDigit(text.charAt(contextStart))) {
                    contextStart++;
                }
            }
            
            // Try to end at a sentence boundary
            if (contextEnd < text.length() - 1) {
                int periodPos = text.indexOf(". ", phrasePos + phrase.length());
                if (periodPos > 0 && periodPos < contextEnd) {
                    contextEnd = periodPos + 1;
                }
            }
            
            // Extract the snippet with context
            String snippet = text.substring(contextStart, contextEnd);
            
            // Add ellipsis if we're not at the beginning/end
            if (contextStart > 0) {
                snippet = "..." + snippet;
            }
            if (contextEnd < text.length()) {
                snippet += "...";
            }
            
            // Highlight the phrase in the snippet
            try {
                // Create a case-insensitive pattern that finds the phrase
                Pattern pattern = Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(snippet);
                
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String match = matcher.group();
                    matcher.appendReplacement(sb, "<strong>" + match + "</strong>");
                }
                matcher.appendTail(sb);
                
                return sb.toString();
            } catch (Exception e) {
                // If regex highlighting fails, use simple string replacement
                logger.debug("Regex highlight failed, using simple replacement for phrase: {}", phrase);
                
                // Find the phrase in our snippet (which may be different from the original text position)
                int snippetPhrasePos = snippet.toLowerCase().indexOf(lowerPhrase);
                if (snippetPhrasePos >= 0) {
                    String exactMatch = snippet.substring(snippetPhrasePos, snippetPhrasePos + phrase.length());
                    snippet = snippet.substring(0, snippetPhrasePos) + 
                              "<strong>" + exactMatch + "</strong>" + 
                              snippet.substring(snippetPhrasePos + phrase.length());
                }
                
                return snippet;
            }
        } catch (Exception e) {
            logger.error("Error generating phrase snippet: {}", e.getMessage());
            return "Content preview unavailable";
        }
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
    

    
    private String generateSnippet(String content, List<String> stemmedWords) {
        try {
            // Parse HTML content
            org.jsoup.nodes.Document parsedDoc = org.jsoup.Jsoup.parse(content);
            String text = parsedDoc.text();
            
            if (text == null || text.isEmpty()) {
                return "No content available";
            }
            
            // Create a search phrase from all terms
            String searchPhrase = String.join(" ", stemmedWords).toLowerCase();
            boolean isExactPhraseSearch = stemmedWords.size() > 1;
                
            // Split text into paragraphs for better context
            String[] paragraphs = text.split("\\. ");
            
            if (paragraphs.length == 0) {
                return "No content available";
            }
            
            // Find paragraph with the exact phrase match
            String bestParagraph = "";
            boolean foundExactMatch = false;
            
            // First prioritize finding the exact phrase
            if (isExactPhraseSearch) {
                for (String paragraph : paragraphs) {
                    if (paragraph != null && paragraph.length() >= 20) {
                        String lowerParagraph = paragraph.toLowerCase();
                        if (lowerParagraph.contains(searchPhrase)) {
                            bestParagraph = paragraph;
                            foundExactMatch = true;
                            logger.debug("Found exact phrase match in snippet: '{}'", searchPhrase);
                            break;
                        }
                    }
                }
            }
            
            // If we didn't find an exact match, look for individual terms
            if (!foundExactMatch) {
                int bestMatchCount = 0;
                
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
                // Try to find a good breakpoint that doesn't cut in the middle of the phrase
                int idealEnd = 297;
                
                if (foundExactMatch) {
                    // Find where the phrase is
                    int phrasePos = bestParagraph.toLowerCase().indexOf(searchPhrase.toLowerCase());
                    
                    // If the phrase would be cut, adjust to include the full phrase
                    if (phrasePos > 0 && phrasePos < idealEnd && phrasePos + searchPhrase.length() > idealEnd) {
                        // Include the full phrase + a bit more
                        idealEnd = phrasePos + searchPhrase.length() + 20;
                        // But don't exceed the paragraph length
                        idealEnd = Math.min(idealEnd, bestParagraph.length());
                    }
                }
                
                bestParagraph = bestParagraph.substring(0, Math.min(idealEnd, bestParagraph.length())) + "...";
            }
            
            // Highlight the content differently based on whether it's a phrase search
            String snippet = bestParagraph;
            
            if (isExactPhraseSearch && foundExactMatch) {
                // For phrase searches, highlight the complete phrase
                try {
                    String safePhrase = Pattern.quote(searchPhrase);
                    String regex = "(?i)" + safePhrase;
                    snippet = snippet.replaceAll(regex, "<strong>$0</strong>");
                } catch (Exception e) {
                    // If regex fails, try simple replacement
                    logger.debug("Regex replacement failed for phrase {}, using simple replace", searchPhrase);
                    
                    int index = snippet.toLowerCase().indexOf(searchPhrase.toLowerCase());
                    if (index >= 0) {
                        String original = snippet.substring(index, index + searchPhrase.length());
                        snippet = snippet.substring(0, index) + 
                                "<strong>" + original + "</strong>" + 
                                snippet.substring(index + searchPhrase.length());
                    }
                }
            } else {
                // For regular searches, highlight each term individually
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
            }
            
            return snippet;
        } catch (Exception e) {
            logger.error("Error generating snippet: {}", e.getMessage());
            return "Content preview unavailable";
        }
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

    /**
     * Generate a clean, meaningful description for search results
     * @param docData Map containing document data including content
     * @return A formatted description snippet
     */
    private String generateSearchResultDescription(Map<String, Object> docData) {
        try {
            // Clean HTML content first
            String content = (String) docData.get("content");
            if (content == null || content.isEmpty()) {
                return "Visit this page to learn more...";
            }
            
            // Use Jsoup to clean and extract text
            org.jsoup.nodes.Document jsoupDoc = org.jsoup.Jsoup.parse(content);
            
            // Remove script and style elements
            jsoupDoc.select("script, style, meta, link, iframe, noscript").remove();
            
            // Get text content from important elements
            String description = "";
            
            // Try to get meta description first
            org.jsoup.nodes.Element metaDesc = jsoupDoc.select("meta[name=description]").first();
            if (metaDesc != null && !metaDesc.attr("content").trim().isEmpty()) {
                description = metaDesc.attr("content").trim();
            }
            
            // If no meta description, try to extract from content
            if (description.isEmpty()) {
                // First try to get content from p tags
                StringBuilder contentBuilder = new StringBuilder();
                for (org.jsoup.nodes.Element p : jsoupDoc.select("p")) {
                    String pText = p.text().trim();
                    if (!pText.isEmpty() && pText.length() > 50) {
                        contentBuilder.append(pText).append(" ");
                        if (contentBuilder.length() > 200) break;
                    }
                }
                
                // If we couldn't get enough from p tags, use the main content
                if (contentBuilder.length() < 100) {
                    // Extract text from body, limited to first 300 chars
                    String bodyText = jsoupDoc.body().text();
                    if (bodyText.length() > 300) {
                        description = bodyText.substring(0, 300) + "...";
                    } else {
                        description = bodyText;
                    }
                } else {
                    description = contentBuilder.toString().trim();
                    if (description.length() > 300) {
                        description = description.substring(0, 300) + "...";
                    }
                }
            }
            
            // Clean up any weird characters or formatting
            description = description.replaceAll("\\s+", " ").trim();
            
            return description;
        } catch (Exception e) {
            // Fallback to a simple description if parsing fails
            return "Visit this page to learn more...";
        }
    }

    /**
     * Format search results to improve readability of descriptions
     * @param results The raw search results
     * @return Formatted results with cleaned descriptions
     */
    public List<Map<String, Object>> formatSearchResults(List<Map<String, Object>> results) {
        return results.stream()
            .map(this::formatSearchResult)
            .collect(Collectors.toList());
    }

    /**
     * Format a single search result for better display
     * @param result The raw search result
     * @return Formatted result with cleaned description
     */
    private Map<String, Object> formatSearchResult(Map<String, Object> result) {
        Map<String, Object> formatted = new HashMap<>(result);
        
        // Format the description/snippet to make it more readable
        String description = (String) result.get("snippet");
        if (description != null) {
            // Remove any base64 or data URIs which can be long and useless
            description = description.replaceAll("data:[^\\s]*;base64,[^\\s]*", "");
            
            // Remove any JS variable declarations
            description = description.replaceAll("var\\s+[^=]+=\\s*[^;]*;", "");
            
            // Remove any CSS style blocks
            description = description.replaceAll("\\{--[^}]*\\}", "");
            
            // Remove any JSON/object literals
            description = description.replaceAll("\\{[\"'][^}]*\\}", "");
            
            // Clean up code formats
            description = description.replaceAll("function\\s*\\([^)]*\\)\\s*\\{[^}]*\\}", "[CODE BLOCK]");
            
            // Truncate if too long
            if (description.length() > 250) {
                // Try to find a good breaking point
                int breakPoint = description.lastIndexOf(". ", 250);
                if (breakPoint > 150) {
                    description = description.substring(0, breakPoint + 1);
                } else {
                    description = description.substring(0, 250) + "...";
                }
            }
            
            // Clean up whitespace
            description = description.replaceAll("\\s+", " ").trim();
            
            // Update the snippet with the cleaned description
            formatted.put("snippet", description);
        }
        
        return formatted;
    }

    /**
     * Search for documents matching the query and return paginated results
     * @param query The search query
     * @param page The page number (1-based)
     * @param pageSize Number of results per page
     * @return List of search results
     */
    public List<Map<String, Object>> search(String query, int page, int pageSize) {
        // Get the query result directly (it will be cached by the controller)
        QueryResult result = processQuery(query);
        
        List<Map<String, Object>> allResults = result.getResults();
        if (allResults == null || allResults.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Apply pagination
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allResults.size());
        
        if (fromIndex >= allResults.size()) {
            return new ArrayList<>();
        }
        
        return allResults.subList(fromIndex, toIndex);
    }
    
    /**
     * Get the total number of results for a query
     * @param query The search query
     * @return Total number of matching documents
     */
    public int getTotalResultsCount(String query) {
        // This method will likely be called by the controller after search(),
        // so we'll keep it simple to let the controller handle caching
        QueryResult result = processQuery(query);
        List<Map<String, Object>> results = result.getResults();
        return results != null ? results.size() : 0;
    }

    /**
     * Process a complex phrase query that uses operators (AND, OR, NOT) between phrases
     * Example: "Football player" OR "Tennis player"
     */
    private QueryResult processComplexPhraseQuery(String query, QueryResult result) {
        logger.info("Processing complex phrase query: '{}'", query);
        
        // Create instance of ComplexPhraseSearching with all necessary dependencies
        ComplexPhraseSearching complexSearcher = new ComplexPhraseSearching(
            query,
            jdbcTemplate,
            wordRepository,
            documentRepository,
            stopWords
        );
        
        // Get the sorted results
        List<Long> matchingDocIds = complexSearcher.getSortedDocumentIds();
        Map<Long, Double> docScores = complexSearcher.getResults();
        
        // Add the phrases to the result
        List<String> phrases = complexSearcher.getPhrases();
        result.setPhrases(phrases);
        
        // Set the operator
        result.setOperator(complexSearcher.getOperator());
        
        // Create mapping for matching documents
        Map<String, List<Long>> matchingDocuments = new HashMap<>();
        for (String phrase : phrases) {
            matchingDocuments.put(phrase, matchingDocIds);
        }
        result.setMatchingDocuments(matchingDocuments);
        
        // Create stemmed words for snippet generation
        List<String> stemmedWords = new ArrayList<>();
        for (String phrase : phrases) {
            // Add each individual word from the phrase
            stemmedWords.addAll(
                Arrays.stream(phrase.split("\\s+"))
                    .map(this::stemWord)
                    .filter(w -> !w.isEmpty())
                    .collect(Collectors.toList())
            );
        }
        result.setStemmedWords(stemmedWords);
        
        // Fetch full document details with snippets
        List<Map<String, Object>> searchResults = fetchDocumentDetails(stemmedWords, matchingDocIds);
        
        // Add scores from complex phrase searcher
        for (Map<String, Object> doc : searchResults) {
            Long docId = ((Number) doc.get("id")).longValue();
            doc.put("score", docScores.getOrDefault(docId, 0.0));
        }
        
        // Sort by relevance score
        searchResults.sort((r1, r2) -> 
            Double.compare(
                ((Number) r2.get("score")).doubleValue(),
                ((Number) r1.get("score")).doubleValue()
            )
        );
        
        // Apply final filtering
        if (!searchResults.isEmpty()) {
            searchResults = filterLowQualityResults(searchResults, stemmedWords, phrases);
            result.setSuggestedQueries(generateSuggestedQueries(query, searchResults));
        }
        
        result.setResults(searchResults);
        logger.info("Complex phrase search completed with {} results", searchResults.size());
        
        return result;
    }
}