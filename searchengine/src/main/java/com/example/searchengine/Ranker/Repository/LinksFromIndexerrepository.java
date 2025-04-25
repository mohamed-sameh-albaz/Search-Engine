package com.example.searchengine.Ranker.Repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.searchengine.Ranker.Entities.LinksFromIndexer;

public interface LinksFromIndexerrepository extends JpaRepository<LinksFromIndexer, Long> {
    // This interface is a repository for the LinksFromIndexer entity.
    // It extends JpaRepository to provide CRUD operations.
    // You can add custom query methods here if needed.

    // Example of a custom query method:
    // List<LinksFromIndexer> findBySomeField(String someField);

}
