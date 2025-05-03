package com.example.searchengine.Crawler.Service;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for efficient document lookups with caching
 */
@Service
public class DocumentLookupService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentLookupService.class);
    
    private final DocumentsRepository documentsRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public DocumentLookupService(DocumentsRepository documentsRepository, JdbcTemplate jdbcTemplate) {
        this.documentsRepository = documentsRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Find document by ID with caching
     * 
     * @param id The document ID
     * @return Optional containing the Document if found
     */
    @Cacheable(value = "documentCache", key = "#id")
    public Optional<Document> findById(Long id) {
        logger.debug("Looking up document by ID: {}", id);
        return documentsRepository.findById(id);
    }

    /**
     * Get document data without loading the entire entity (more efficient)
     * 
     * @param id The document ID
     * @return Map with document fields or null if not found
     */
    @Cacheable(value = "documentCache", key = "'data_'+#id")
    public Map<String, Object> getDocumentData(Long id) {
        try {
            String sql = "SELECT id, url, title, content FROM documents WHERE id = ?";
            return jdbcTemplate.queryForMap(sql, id);
        } catch (Exception e) {
            logger.error("Error getting document data for ID {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Get snippet-ready document data (id, url, title) without the content
     * 
     * @param id The document ID
     * @return Map with basic document fields or empty map if not found
     */
    @Cacheable(value = "documentCache", key = "'snippet_'+#id")
    public Map<String, Object> getDocumentForSnippet(Long id) {
        try {
            String sql = "SELECT id, url, title FROM documents WHERE id = ?";
            return jdbcTemplate.queryForMap(sql, id);
        } catch (Exception e) {
            logger.error("Error getting snippet data for document ID {}: {}", id, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Count the total number of documents (cached)
     * 
     * @return Total document count
     */
    @Cacheable(value = "documentCache", key = "'count'")
    public long count() {
        return documentsRepository.count();
    }
} 