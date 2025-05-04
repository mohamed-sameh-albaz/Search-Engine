package com.example.searchengine.Indexer.Repository;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordDocumentMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WordDocumentMetricsRepository extends JpaRepository<WordDocumentMetrics, Long> {
    List<WordDocumentMetrics> findByWordId(Long wordId);
    
    List<WordDocumentMetrics> findByDocumentId(Long documentId);
    
    Optional<WordDocumentMetrics> findByWordAndDocument(Word word, Document document);
    
    @Query("SELECT m FROM WordDocumentMetrics m WHERE m.word.word = ?1 ORDER BY m.tfIdfScore DESC")
    List<WordDocumentMetrics> findTopDocumentsByWordText(String wordText);
    
    @Query(value = "SELECT m.* FROM word_document_metrics m " +
            "JOIN words w ON m.word_id = w.id " +
            "WHERE w.word = ?1 " +
            "ORDER BY m.tf_idf_score DESC LIMIT ?2", nativeQuery = true)
    List<WordDocumentMetrics> findTopNDocumentsByWordText(String wordText, int limit);
    
    @Query("SELECT DISTINCT m.document FROM WordDocumentMetrics m " +
            "WHERE m.word.word IN ?1 " +
            "GROUP BY m.document " +
            "HAVING COUNT(DISTINCT m.word) = ?2 " +
            "ORDER BY SUM(m.tfIdfScore) DESC")
    List<Document> findDocumentsContainingAllWords(List<String> words, int wordCount);
} 