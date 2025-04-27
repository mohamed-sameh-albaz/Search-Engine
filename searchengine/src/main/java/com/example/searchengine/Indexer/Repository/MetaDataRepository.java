package com.example.searchengine.Indexer.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.searchengine.Indexer.Entities.Metadata;

public interface MetaDataRepository extends JpaRepository<Metadata, Long> {
    // This interface is responsible for:
    // 1) Stores additional document metadata (e.g., categories, tags, authors) to
    // support filtering, faceting, or enriched search results.
    // It extends JpaRepository to provide CRUD operations for MetaData entities.
    // You can add custom query methods here if needed.

}
