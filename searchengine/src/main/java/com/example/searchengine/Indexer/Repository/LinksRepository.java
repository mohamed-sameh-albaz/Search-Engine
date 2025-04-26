package com.example.searchengine.Indexer.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.searchengine.Indexer.Entities.IndexerLinks;

public interface LinksRepository extends JpaRepository<IndexerLinks, Integer> {
    // This interface is responsible for managing the links in the database.
    // It extends JpaRepository to provide CRUD operations for IndexerLinks entities.
    // You can add custom query methods here if needed.

}
