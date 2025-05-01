package com.example.searchengine.Crawler.CrawlerMainProcess;

import java.util.*;

import org.springframework.stereotype.Service;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;

import lombok.AllArgsConstructor;
@Service
@AllArgsConstructor
public class sychronServeDataBase {
    DocumentsRepository documentsRepository;
    public Object saveToDatabase(Document document) {
   // Save the document to the database using the repository
        return documentsRepository.save(document);
    }
    public List<Document> getAllVisited() {
        // Retrieve all documents with status "visited" from the database using the repository
        return documentsRepository.findByStatus("visited");
    }
    public Map<String, String> getUrlsandContents() {
        // Retrieve all URLs and contents from the database using the repository
        List<String> urls = documentsRepository.getUrls();
        List<String> contents = documentsRepository.getContents();
        Map<String, String> urlContentMap = new HashMap<>();
        for (int i = 0; i < urls.size(); i++) {
            urlContentMap.put(urls.get(i), contents.get(i));
        }
        return urlContentMap;
    }
    public List<Document> getAllCrawled() {
        // Retrieve all documents with status "not visited" from the database using the repository
        return documentsRepository.findByStatus("");
    }
    public boolean isExists(String url) {
        // Check if a document with the given URL exists in the database using the repository
        return documentsRepository.findByStatus("visited").stream().anyMatch(doc -> doc.getUrl().equals(url));
    }
    public void updateDocument(Document document) {
        // Update the document in the database using the repository
        documentsRepository.save(document);
    }
    
}

