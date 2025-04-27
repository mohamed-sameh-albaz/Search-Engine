package com.example.searchengine.Indexer.Repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.searchengine.Indexer.Entities.TermDocumentMapping;
import com.example.searchengine.Indexer.Entities.TermDocumentMappingId;
import com.example.searchengine.Crawler.Entities.Document;

public interface TermDocumentMappingRepository extends JpaRepository<TermDocumentMapping, TermDocumentMappingId> {

    // This interface is responsible for:
    // 1) Mapping terms to documents,
    // 2) storing term frequency,
    // 3) position data to enable fast keyword-based searches.
    // It extends JpaRepository to provide CRUD operations for TermDocumentMappings
    // entities.
    // You can add custom query methods here if needed.

}
