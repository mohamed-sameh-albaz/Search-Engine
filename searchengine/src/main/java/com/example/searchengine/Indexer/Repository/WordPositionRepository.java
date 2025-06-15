package com.example.searchengine.Indexer.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordPosition;

@Repository
public interface WordPositionRepository extends JpaRepository<WordPosition, Long> {
    
    // Find all positions for a word in a document
    List<WordPosition> findByWordAndDocument(Word word, Document document);
    
    // Find all positions for a word in a document ordered by position
    List<WordPosition> findByWordAndDocumentOrderByPosition(Word word, Document document);
    
    // Find all words at a specific position in a document
    List<WordPosition> findByDocumentAndPosition(Document document, Integer position);
    
    // Find words with specific tag in a document
    List<WordPosition> findByDocumentAndTag(Document document, String tag);
    
    // Find consecutive positions of words in a document (for phrase search)
    @Query("SELECT wp FROM WordPosition wp WHERE wp.document.id = :docId AND wp.word.id IN :wordIds ORDER BY wp.position")
    List<WordPosition> findConsecutivePositions(Long docId, List<Long> wordIds);
    
    // Delete all positions for a document
    void deleteByDocument(Document document);
} 