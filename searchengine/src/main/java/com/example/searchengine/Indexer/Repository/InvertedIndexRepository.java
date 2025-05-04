package com.example.searchengine.Indexer.Repository;

import com.example.searchengine.Indexer.Entities.InvertedIndex;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Crawler.Entities.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvertedIndexRepository extends JpaRepository<InvertedIndex, Long> {
  Optional<InvertedIndex> findByWordAndDocument(Word word, Document document);

  @Query("SELECT ii FROM InvertedIndex ii JOIN FETCH ii.document WHERE ii.word = :word")
  List<InvertedIndex> findByWord(Word word, Pageable pageable);

  @Modifying
  @Query(value = "INSERT INTO InvertedIndex (word_id, doc_id, frequency) " +
      "VALUES (:wordId, :docId, :frequency) " +
      "ON CONFLICT (word_id, doc_id) " +
      "DO UPDATE SET frequency = inverted_index.frequency + :frequency", nativeQuery = true)
  void upsert(@Param("wordId") Long wordId, @Param("docId") Long docId, @Param("frequency") Integer frequency);

  List<InvertedIndex> findByWordId(Long wordId);

  List<InvertedIndex> findByWord(Word word);

  long countByWord(Word word);
}