package com.example.searchengine.Indexer.Entities;

import com.example.searchengine.Crawler.Entities.Document;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "word_document_tags", indexes = {
        @Index(name = "idx_word_document_tags_word_id", columnList = "word_id"),
        @Index(name = "idx_word_document_tags_word_doc", columnList = "word_id, doc_id") }, uniqueConstraints = {
                @UniqueConstraint(name = "word_document_tags_word_id_doc_id_tag_unique", columnNames = { "word_id",
                        "doc_id", "tag" })
        })
@Getter
@Setter
public class WordDocumentTag {
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
    private String tag;
}