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
            if (!isPhraseQuery || phrases.isEmpty()) {
                result.setResults(fetchRegularSearchResults(stemmedWords, matchingDocuments));
            } else {
                result.setResults(fetchPhraseSearchResults(phrases.get(0), matchingDocuments));
            }
            
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
            
            for (String word : words) {
                word = word.trim();
                if (!word.isEmpty() && !stopWords.contains(word) && !word.equals("and")) {
                    String stemmed = stemWord(word);
                    if (!stemmed.isEmpty()) {
                        stemmedWords.add(stemmed);
                    
                    // Find documents containing this word
                    Optional<Word> wordEntity = wordRepository.findByWord(stemmed);
                    if (wordEntity.isPresent()) {
                        List<InvertedIndex> indices = invertedIndexRepository.findAll().stream()
                            .filter(entry -> entry.getWord().getId().equals(wordEntity.get().getId()))
                            .collect(Collectors.toList());
                        List<Long> docIds = indices.stream()
                            .map(index -> index.getDocument().getId())
                            .collect(Collectors.toList());
                        matchingDocuments.put(stemmed, docIds);
                    } else {
                        matchingDocuments.put(stemmed, new ArrayList<>());
                    }
                }
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
        
        // Get documents containing all words in the phrase
        Set<Long> docsWithAllWords = null;
        
        for (String stemmed : filteredAndStemmed) {
            Optional<Word> wordEntity = wordRepository.findByWord(stemmed);
            if (wordEntity.isPresent()) {
                List<InvertedIndex> indices = invertedIndexRepository.findAll().stream()
                    .filter(entry -> entry.getWord().getId().equals(wordEntity.get().getId()))
                    .collect(Collectors.toList());
                Set<Long> docIds = indices.stream()
                    .map(index -> index.getDocument().getId())
                    .collect(Collectors.toSet());
                
                if (docsWithAllWords == null) {
                    docsWithAllWords = new HashSet<>(docIds);
                } else {
                    docsWithAllWords.retainAll(docIds);
                }
            } else {
                // If any word is not found, there are no matching documents
                docsWithAllWords = new HashSet<>();
                break;
            }
        }
        
        if (docsWithAllWords == null || docsWithAllWords.isEmpty()) {
            matchingDocuments.put(phrase, new ArrayList<>());
            return;
        }
        
        // For each document, check if the words appear in sequence
        List<Long> matchingDocs = new ArrayList<>();
        
        for (Long docId : docsWithAllWords) {
            if (checkPhraseMatch(docId, filteredAndStemmed)) {
                matchingDocs.add(docId);
            }
        }
        
        matchingDocuments.put(phrase, matchingDocs);
    }
    
    private boolean checkPhraseMatch(Long docId, List<String> stemmedPhrase) {
        // Get all occurrences of the first word in the document
        Word firstWordEntity = wordRepository.findByWord(stemmedPhrase.get(0)).orElse(null);
        if (firstWordEntity == null) {
            return false;
        }
        
        List<WordDocumentTag> firstWordTags = wordDocumentTagRepository.findAll().stream()
            .filter(tag -> tag.getWord().getId().equals(firstWordEntity.getId()) && 
                          tag.getDocument().getId().equals(docId))
            .collect(Collectors.toList());
        
        for (WordDocumentTag firstTag : firstWordTags) {
            // For each occurrence of the first word, check if subsequent words follow in sequence
            int paragraphIdx = firstTag.getParagraphIndex() != null ? firstTag.getParagraphIndex() : 0;
            int wordIdx = firstTag.getWordIndex() != null ? firstTag.getWordIndex() : 0;
            boolean allMatch = true;
            
            for (int i = 1; i < stemmedPhrase.size(); i++) {
                Word nextWordEntity = wordRepository.findByWord(stemmedPhrase.get(i)).orElse(null);
                if (nextWordEntity == null) {
                    allMatch = false;
                    break;
                }
                
                // Check if this word appears at the next position in the same paragraph
                List<WordDocumentTag> nextWordTags = wordDocumentTagRepository.findAll().stream()
                    .filter(tag -> tag.getWord().getId().equals(nextWordEntity.getId()) && 
                                 tag.getDocument().getId().equals(docId))
                    .collect(Collectors.toList());
                
                boolean foundNextWord = false;
                for (WordDocumentTag nextTag : nextWordTags) {
                    // Check if this tag occurs in the expected position
                    if (nextTag.getParagraphIndex() != null && nextTag.getWordIndex() != null &&
                        nextTag.getParagraphIndex().equals(paragraphIdx) && 
                        nextTag.getWordIndex().equals(wordIdx + i)) {
                        foundNextWord = true;
                        break;
                    }
                }
                
                if (!foundNextWord) {
                    allMatch = false;
                    break;
                }
            }
            
            if (allMatch) {
                return true;
            }
        }
        
        return false;
    }
    
    private List<Map<String, Object>> fetchRegularSearchResults(List<String> stemmedWords, Map<String, List<Long>> matchingDocuments) {
        // For regular search, we combine all matching documents
        Set<Long> allDocs = new HashSet<>();
        for (List<Long> docs : matchingDocuments.values()) {
            allDocs.addAll(docs);
        }
        
        return fetchDocumentDetails(stemmedWords, new ArrayList<>(allDocs));
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
    
    private List<Map<String, Object>> fetchDocumentDetails(List<String> stemmedWords, List<Long> docIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        for (Long docId : docIds) {
            Optional<Document> doc = documentRepository.findById(docId);
            if (doc.isPresent()) {
                Map<String, Object> result = new HashMap<>();
                result.put("id", doc.get().getId());
                result.put("url", doc.get().getUrl());
                result.put("title", doc.get().getTitle());
                
                // Generate a snippet highlighting the query terms
                String snippet = generateSnippet(doc.get().getContent(), stemmedWords);
                result.put("snippet", snippet);
                
                // Calculate relevance score
                double score = calculateRelevanceScore(docId, stemmedWords);
                result.put("score", score);
                
                results.add(result);
            }
        }
        
        // Sort results by score, descending
        results.sort((r1, r2) -> Double.compare((double)r2.get("score"), (double)r1.get("score")));
        
        return results;
    }
    
    private String generateSnippet(String content, List<String> stemmedWords) {
        // Parse HTML content
        org.jsoup.nodes.Document parsedDoc = org.jsoup.Jsoup.parse(content);
        String text = parsedDoc.text();
        
        // Find the best paragraph containing the query words
        String[] paragraphs = text.split("\\. ");
        
        int bestMatchCount = 0;
        String bestParagraph = "";
        
        for (String paragraph : paragraphs) {
            if (paragraph.length() < 20) continue; // Skip very short paragraphs
            
            String lowerParagraph = paragraph.toLowerCase();
            int matchCount = 0;
            
            for (String word : stemmedWords) {
                // Check if the stemmed word exists in the paragraph
                List<String> paragraphWords = Arrays.asList(lowerParagraph.split("\\s+"));
                List<String> stemmedParagraphWords = paragraphWords.stream()
                    .map(this::stemWord)
                    .collect(Collectors.toList());
                
                if (stemmedParagraphWords.contains(word)) {
                    matchCount++;
                }
            }
            
            if (matchCount > bestMatchCount) {
                bestMatchCount = matchCount;
                bestParagraph = paragraph;
            }
        }
        
        // If no good paragraph was found, take the first substantial one
        if (bestParagraph.isEmpty() && paragraphs.length > 0) {
            for (String paragraph : paragraphs) {
                if (paragraph.length() >= 50) {
                    bestParagraph = paragraph;
                    break;
                }
            }
        }
        
        // Limit snippet length
        if (bestParagraph.length() > 200) {
            bestParagraph = bestParagraph.substring(0, 197) + "...";
        }
        
        return bestParagraph;
    }
    
    private double calculateRelevanceScore(Long docId, List<String> stemmedWords) {
        double score = 0.0;
        
        // Get total number of documents for IDF calculation
        long totalDocs = documentRepository.count();
        
        for (String word : stemmedWords) {
            Optional<Word> wordEntity = wordRepository.findByWord(word);
            if (wordEntity.isPresent()) {
                // Calculate TF (term frequency)
                Optional<InvertedIndex> indexEntry = invertedIndexRepository.findAll().stream()
                    .filter(entry -> entry.getWord().getId().equals(wordEntity.get().getId()) && 
                                   entry.getDocument().getId().equals(docId))
                    .findFirst();
                
                if (indexEntry.isPresent()) {
                    int termFreq = indexEntry.get().getFrequency();
                    
                    // Calculate IDF (inverse document frequency)
                    long docsWithTerm = invertedIndexRepository.findAll().stream()
                        .filter(entry -> entry.getWord().getId().equals(wordEntity.get().getId()))
                        .count();
                    double idf = Math.log10((double)totalDocs / (docsWithTerm + 1));
                    
                    // TF-IDF score
                    double tfIdf = termFreq * idf;
                    
                    // Add to total score
                    score += tfIdf;
                    
                    // Boost score for words in important tags (title, h1, etc.)
                    List<WordDocumentTag> tags = wordDocumentTagRepository.findAll().stream()
                        .filter(tag -> tag.getWord().getId().equals(wordEntity.get().getId()) && 
                                     tag.getDocument().getId().equals(docId))
                        .collect(Collectors.toList());
                    
                    for (WordDocumentTag tag : tags) {
                        if (tag.getTag().equals("title")) {
                            score += tfIdf * 3;
                        } else if (tag.getTag().equals("h1")) {
                            score += tfIdf * 2;
                        } else if (tag.getTag().startsWith("h")) {
                            score += tfIdf * 1.5;
                        }
                    }
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
} 