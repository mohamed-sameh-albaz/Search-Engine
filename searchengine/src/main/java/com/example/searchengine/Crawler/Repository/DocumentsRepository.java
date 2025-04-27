package com.example.searchengine.Crawler.Repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.searchengine.Crawler.Entities.Document;

public interface DocumentsRepository extends JpaRepository<Document, Long> {
    // This interface extends JpaRepository, which provides CRUD operations for the
    // FullLinks entity.
    // You can add custom query methods here if needed.

}
