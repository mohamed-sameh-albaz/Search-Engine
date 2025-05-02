package com.example.searchengine.Indexer.Entities;

import com.example.searchengine.Crawler.Entities.Document;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "inverted_index", indexes = {
        @Index(name = "idx_inverted_index_word_id", columnList = "word_id"),
        @Index(name = "idx_inverted_index_word_doc", columnList = "word_id, doc_id") }, uniqueConstraints = {
                @UniqueConstraint(name = "inverted_index_word_id_doc_id_unique", columnNames = { "word_id", "doc_id" })
        })
        
@Getter
@Setter
public class InvertedIndex {
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
    private Integer frequency;
}