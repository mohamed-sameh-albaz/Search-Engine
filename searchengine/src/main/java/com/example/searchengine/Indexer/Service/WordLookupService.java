package com.example.searchengine.Indexer.Service;

import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for efficient word lookups with caching
 */
@Service
public class WordLookupService {

    private static final Logger logger = LoggerFactory.getLogger(WordLookupService.class);
    
    private final WordRepository wordRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public WordLookupService(WordRepository wordRepository, JdbcTemplate jdbcTemplate) {
        this.wordRepository = wordRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Lookup a word by its text with caching
     * 
     * @param word The word text to lookup
     * @return Optional containing the Word if found
     */
    @Cacheable(value = "wordCache", key = "#word")
    public Optional<Word> findByWord(String word) {
        logger.debug("Looking up word: {}", word);
        return wordRepository.findByWord(word);
    }

    /**
     * Get document IDs for a word using optimized query with pagination
     * 
     * @param wordId The word ID to lookup
     * @param limit Maximum number of document IDs to return
     * @return List of document IDs
     */
    public List<Long> getDocumentIdsForWord(Long wordId, int limit) {
        return jdbcTemplate.query(
            "SELECT document_id FROM inverted_index WHERE word_id = ? LIMIT ?",
            (rs, rowNum) -> rs.getLong("document_id"),
            wordId, limit
        );
    }

    /**
     * Count documents containing a word
     * 
     * @param wordId The word ID to check
     * @return Count of documents containing the word
     */
    public int countDocumentsWithWord(Long wordId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inverted_index WHERE word_id = ?",
            Integer.class,
            wordId
        );
    }
} 