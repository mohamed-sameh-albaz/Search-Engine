package com.example.searchengine.Crawler.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.searchengine.Crawler.Entities.Document;
import java.util.*;
public interface DocumentsRepository extends JpaRepository<Document, Long> {
    // This interface extends JpaRepository, which provides CRUD operations for the
    // FullLinks entity.
    // You can add custom query methods here if needed.
    List<Document> findByStatus(String status);// Custom query method to find documentsby status
    @Query("SELECT d.url FROM Document d")
    List<String> getUrls(); 
    @Query("SELECT d.content FROM Document d")
    List<String> getContents();
    @Query("Select url, content, title from Document")
    List<Object[]> getUrlsandContents(); // Custom query to get URLs and contents
}
