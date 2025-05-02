package com.example.searchengine.Crawler.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.searchengine.Crawler.Entities.Document;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    Optional<Document> findByUrl(String url);
}