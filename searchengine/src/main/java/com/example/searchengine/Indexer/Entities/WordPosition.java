package com.example.searchengine.Indexer.Entities;

import com.example.searchengine.Crawler.Entities.Document;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "word_position", indexes = {
        @Index(name = "idx_word_position_word_id", columnList = "word_id"),
        @Index(name = "idx_word_position_doc_id", columnList = "doc_id"),
        @Index(name = "idx_word_position_position", columnList = "position"),
        @Index(name = "idx_word_position_word_doc", columnList = "word_id, doc_id")
})
@Getter
@Setter
public class WordPosition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "word_id", nullable = false)
    private Word word;

    @ManyToOne
    @JoinColumn(name = "doc_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private Integer position;
    
    @Column(length = 10)
    private String tag;
} 