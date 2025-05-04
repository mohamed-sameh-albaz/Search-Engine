package com.example.searchengine.Indexer.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.searchengine.Indexer.Entities.Word;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WordRepository extends JpaRepository<Word, Long> {
    Optional<Word> findByWord(String word);

    List<Word> findByWordIn(List<String> words);

    @Modifying
    @Query("UPDATE Word w SET w.totalFrequency = w.totalFrequency + :frequency WHERE w.id = :wordId")
    void incrementTotalFrequency(@Param("wordId") Long wordId, @Param("frequency") Long frequency);

}
