package com.example.searchengine.Indexer.Entities;

import com.example.searchengine.Crawler.Entities.Document;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "word_document_metrics", indexes = {
        @Index(name = "idx_metrics_word_id", columnList = "word_id"),
        @Index(name = "idx_metrics_doc_id", columnList = "doc_id"),
        @Index(name = "idx_metrics_word_doc", columnList = "word_id, doc_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "metrics_word_id_doc_id_unique", columnNames = {"word_id", "doc_id"})
})
@Getter
@Setter
public class WordDocumentMetrics {
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
    
    @Column(nullable = false)
    private Double termFrequency;
    
    @Column(nullable = false)
    private Double tfIdfScore;
    
    @Column(nullable = false)
    private Double normalizedScore;
} 