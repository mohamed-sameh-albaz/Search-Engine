package com.example.searchengine.Indexer.Repository;

import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordDocumentTag;
import com.example.searchengine.Crawler.Entities.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WordDocumentTagRepository extends JpaRepository<WordDocumentTag, Long> {
  Optional<WordDocumentTag> findByWordAndDocumentAndTag(Word word, Document document, String tag);

  @Query("SELECT wdt FROM WordDocumentTag wdt JOIN FETCH wdt.document WHERE wdt.word = :word")
  List<WordDocumentTag> findByWord(Word word, Pageable pageable);

  @Modifying
  @Query(value = "INSERT INTO WordDocumentTag (word_id, doc_id, frequency, tag) " +
      "VALUES (:wordId, :docId, :frequency, :tag) " +
      "ON CONFLICT (word_id, doc_id, tag) " +
      "DO UPDATE SET frequency = WordDocumentTag.frequency + :frequency", nativeQuery = true)
  void upsert(@Param("wordId") Long wordId, @Param("docId") Long docId,
      @Param("frequency") Integer frequency, @Param("tag") String tag);
}