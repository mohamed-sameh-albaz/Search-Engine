package com.example.searchengine.Query;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ComplexPhraseSearching {
    private static final Logger logger = LoggerFactory.getLogger(ComplexPhraseSearching.class);
    private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("\\s+(AND|OR|NOT)\\s+", Pattern.CASE_INSENSITIVE);

    private final String query;
    private final JdbcTemplate jdbcTemplate;
    private final WordRepository wordRepository;
    private final DocumentsRepository documentRepository;
    private final Set<String> stopWords;
    private final List<String> phrases;
    private final String operator;
    private final List<Long> sortedDocumentIds;
    private final Map<Long, Double> docScores;

    public ComplexPhraseSearching(
            String query,
            JdbcTemplate jdbcTemplate,
            WordRepository wordRepository,
            DocumentsRepository documentRepository,
            Set<String> stopWords) {
        this.query = query;
        this.jdbcTemplate = jdbcTemplate;
        this.wordRepository = wordRepository;
        this.documentRepository = documentRepository;
        this.stopWords = stopWords;
        this.phrases = new ArrayList<>();
        this.sortedDocumentIds = new ArrayList<>();
        this.docScores = new HashMap<>();

        // Parse operator and phrases
        Matcher operatorMatcher = OPERATOR_PATTERN.matcher(query);
        String detectedOperator = null;
        if (operatorMatcher.find()) {
            detectedOperator = operatorMatcher.group(1).toUpperCase();
        }
        this.operator = detectedOperator;

        // Extract phrases
        Matcher phraseMatcher = PHRASE_PATTERN.matcher(query);
        while (phraseMatcher.find()) {
            String phrase = phraseMatcher.group(1).trim();
            if (!phrase.isEmpty()) {
                phrases.add(phrase);
            }
        }

        // Process the query
        processQuery();
    }

    private void processQuery() {
        if (phrases.isEmpty() || operator == null) {
            logger.warn("Invalid complex phrase query: {}", query);
            return;
        }

        // Map to store document IDs for each phrase
        Map<String, List<Long>> phraseDocIds = new HashMap<>();

        // Find documents for each phrase
        for (String phrase : phrases) {
            List<Long> docIds = findDocumentsForPhrase(phrase);
            phraseDocIds.put(phrase, docIds);
            logger.info("Phrase '{}' matched {} documents", phrase, docIds.size());
        }

        // Combine document IDs based on operator
        Set<Long> resultDocIds = new HashSet<>();
        if (operator.equals("AND") && phrases.size() >= 2) {
            // Initialize with the first phrase's documents
            resultDocIds.addAll(phraseDocIds.get(phrases.get(0)));
            // Intersect with remaining phrases
            for (int i = 1; i < phrases.size(); i++) {
                resultDocIds.retainAll(phraseDocIds.get(phrases.get(i)));
            }
        } else if (operator.equals("OR")) {
            // Union of all phrases' documents
            for (String phrase : phrases) {
                resultDocIds.addAll(phraseDocIds.get(phrase));
            }
        } else if (operator.equals("NOT") && phrases.size() >= 2) {
            // Documents with the first phrase but not the second
            Set<Long> leftDocs = new HashSet<>(phraseDocIds.get(phrases.get(0)));
            Set<Long> rightDocs = new HashSet<>(phraseDocIds.get(phrases.get(1)));
            resultDocIds.addAll(leftDocs);
            resultDocIds.removeAll(rightDocs);
        }

        // Calculate scores for each document
        for (Long docId : resultDocIds) {
            double score = calculateDocumentScore(docId, phrases);
            docScores.put(docId, score);
        }

        // Sort document IDs by score (descending)
        sortedDocumentIds.addAll(resultDocIds);
        sortedDocumentIds.sort((id1, id2) -> Double.compare(docScores.getOrDefault(id2, 0.0), docScores.getOrDefault(id1, 0.0)));

        logger.info("Complex phrase query '{}' with operator '{}' returned {} documents", query, operator, sortedDocumentIds.size());
    }

    private List<Long> findDocumentsForPhrase(String phrase) {
        List<Long> docIds = new ArrayList<>();
        try {
            // Split phrase into words and stem them
            List<String> stemmedWords = Arrays.stream(phrase.toLowerCase().split("\\s+"))
                    .filter(w -> !stopWords.contains(w))
                    .map(this::stemWord)
                    .filter(w -> !w.isEmpty())
                    .collect(Collectors.toList());

            if (stemmedWords.isEmpty()) {
                return docIds;
            }

            // Use the first word to narrow down the document set
            String firstWord = stemmedWords.get(0);
            Optional<Word> wordEntity = wordRepository.findByWord(firstWord);
            if (!wordEntity.isPresent()) {
                return docIds;
            }

            // Get documents containing the first word
            List<Long> candidateDocIds = jdbcTemplate.query(
                    "SELECT doc_id FROM inverted_index WHERE word_id = ? LIMIT 1000",
                    (rs, rowNum) -> rs.getLong("doc_id"),
                    wordEntity.get().getId()
            );

            // Check each candidate document for the full phrase
            for (Long docId : candidateDocIds) {
                if (checkPhraseMatch(docId, stemmedWords)) {
                    docIds.add(docId);
                }
            }
        } catch (Exception e) {
            logger.error("Error finding documents for phrase '{}': {}", phrase, e.getMessage());
        }
        return docIds;
    }

    private boolean checkPhraseMatch(Long docId, List<String> stemmedWords) {
        try {
            Optional<Document> docOpt = documentRepository.findById(docId);
            if (!docOpt.isPresent() || docOpt.get().getContent() == null) {
                return false;
            }

            Document doc = docOpt.get();
            String content = doc.getContent().toLowerCase();
            String title = doc.getTitle() != null ? doc.getTitle().toLowerCase() : "";
            String phrase = String.join(" ", stemmedWords);

            // Check title and content for the phrase
            return title.contains(phrase) || content.contains(phrase);
        } catch (Exception e) {
            logger.warn("Error checking phrase match for document {}: {}", docId, e.getMessage());
            return false;
        }
    }

    private double calculateDocumentScore(Long docId, List<String> phrases) {
        double score = 0.0;
        try {
            Optional<Document> docOpt = documentRepository.findById(docId);
            if (!docOpt.isPresent()) {
                return 0.0;
            }

            Document doc = docOpt.get();
            String title = doc.getTitle() != null ? doc.getTitle().toLowerCase() : "";
            String content = doc.getContent() != null ? doc.getContent().toLowerCase() : "";
            long totalDocs = documentRepository.count();

            for (String phrase : phrases) {
                List<String> stemmedWords = Arrays.stream(phrase.toLowerCase().split("\\s+"))
                        .filter(w -> !stopWords.contains(w))
                        .map(this::stemWord)
                        .filter(w -> !w.isEmpty())
                        .collect(Collectors.toList());

                for (String word : stemmedWords) {
                    Optional<Word> wordEntity = wordRepository.findByWord(word);
                    if (!wordEntity.isPresent()) {
                        continue;
                    }

                    Integer frequency = jdbcTemplate.queryForObject(
                            "SELECT frequency FROM inverted_index WHERE word_id = ? AND doc_id = ?",
                            Integer.class, wordEntity.get().getId(), docId);
                    if (frequency == null || frequency == 0) {
                        continue;
                    }

                    Long docsWithWord = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM inverted_index WHERE word_id = ?",
                            Long.class, wordEntity.get().getId());
                    if (docsWithWord == null || docsWithWord == 0) {
                        docsWithWord = 1L;
                    }

                    double tf = (double) frequency / Math.max(1, content.split("\\s+").length);
                    double idf = Math.log10((double) totalDocs / docsWithWord);
                    score += tf * idf;

                    if (title.contains(word)) {
                        score += tf * idf * 3.0; // Title boost
                    }
                }

                // Boost for exact phrase match
                String phraseLower = phrase.toLowerCase();
                if (title.contains(phraseLower) || content.contains(phraseLower)) {
                    score *= 2.0;
                }
            }
        } catch (Exception e) {
            logger.error("Error calculating score for document {}: {}", docId, e.getMessage());
        }
        return score;
    }

    private String stemWord(String word) {
        // Reuse QueryService's PorterStemmer if available, or implement a simple one
        if (word == null || word.isEmpty()) {
            return word;
        }
        // Placeholder: Replace with actual PorterStemmer from QueryService
        return word.toLowerCase(); // Simplified for this example
    }

    // Getters
    public List<Long> getSortedDocumentIds() {
        return sortedDocumentIds;
    }

    public Map<Long, Double> getResults() {
        return docScores;
    }

    public List<String> getPhrases() {
        return phrases;
    }

    public String getOperator() {
        return operator;
    }
}