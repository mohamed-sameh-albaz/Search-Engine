package com.example.searchengine.Indexer.Entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "word_idf", indexes = {
        @Index(name = "idx_word_idf_word_id", columnList = "word_id")
})
@Getter
@Setter
public class WordIdf {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "word_id", nullable = false, unique = true)
    private Word word;

    @Column(nullable = false)
    private Double idfValue;
    
    @Column(nullable = false)
    private Long documentFrequency;
    
    @Column(nullable = false)
    private Long totalDocuments;
} 