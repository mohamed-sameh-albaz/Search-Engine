package com.example.searchengine.Indexer.Entities;

import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.*;
import com.example.searchengine.Crawler.Entities.Document;

@Entity
@Table(name = "term_document_mapping")
@IdClass(TermDocumentMappingId.class)
@Setter
@Getter

public class TermDocumentMapping {

    @Id
    @ManyToOne
    @JoinColumn(name = "term_id", nullable = false)
    private Term term;

    @Id
    @ManyToOne
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private Integer frequency;

    @Column
    private String positions;
}