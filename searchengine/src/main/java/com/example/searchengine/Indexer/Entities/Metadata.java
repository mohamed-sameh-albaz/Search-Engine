package com.example.searchengine.Indexer.Entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import com.example.searchengine.Crawler.Entities.Document;

@Entity
@Table(name = "metadata")
@Setter
@Getter

public class Metadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false, length = 100)
    private String key;

    @Column(nullable = false, length = 255)
    private String value;
}