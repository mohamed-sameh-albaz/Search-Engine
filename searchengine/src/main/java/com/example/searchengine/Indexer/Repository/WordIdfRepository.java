package com.example.searchengine.Indexer.Repository;

import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordIdf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WordIdfRepository extends JpaRepository<WordIdf, Long> {
    Optional<WordIdf> findByWordId(Long wordId);
    
    Optional<WordIdf> findByWord(Word word);
    
    @Query("SELECT w.word.word, w.idfValue FROM WordIdf w")
    List<Object[]> findAllWordIdfPairs();
    
    @Query("SELECT w FROM WordIdf w WHERE w.word.word = ?1")
    Optional<WordIdf> findByWordText(String wordText);
} 