package com.example.searchengine.Crawler.CrawlerMainProcess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Entities.RelatedLinks;
import com.example.searchengine.Crawler.Entities.RelatedLinksID;
import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Crawler.Repository.RelatedLinksRepository;

import lombok.AllArgsConstructor;


@Service
@AllArgsConstructor
public class ServeDataBase {
    DocumentsRepository documentsRepository;
    RelatedLinksRepository relatedLinksRepository;
    
    private static final int MAX_URL_LENGTH = 250; // Leave some margin from 255
    
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
    
    /**
     * Save relationship between parent and child documents, handling URLs that are too long
     */
    public void saveRelatedLinks(String parentDocid, String childDocid) {
        try {
            // Handle long URLs by truncating or hashing if needed
            String parentId = processLongUrl(parentDocid);
            String childId = processLongUrl(childDocid);
            
            // Create a new RelatedLinks entity and save it to the database
            RelatedLinksID relatedLinksID = new RelatedLinksID(parentId, childId);
            RelatedLinks relatedLinks = new RelatedLinks(relatedLinksID);
            relatedLinksRepository.save(relatedLinks);
        } catch (Exception e) {
            System.out.println("Error saving related links for: " + 
                               shortenForLogging(parentDocid) + " -> " + 
                               shortenForLogging(childDocid) + 
                               " Error: " + e.getMessage());
        }
    }
    
    /**
     * Process potentially long URLs to fit in database fields
     */
    private String processLongUrl(String url) {
        if (url.length() <= MAX_URL_LENGTH) {
            return url; // Short enough, use as is
        }
        
        try {
            // Hash the URL for consistent results
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            // Use the first part of the URL plus hash
            int urlPartLength = MAX_URL_LENGTH - 65; // Allow space for hash and separator
            String urlStart = url.substring(0, Math.min(urlPartLength, url.length()));
            return urlStart + "___" + hexString.toString().substring(0, 60);
            
        } catch (NoSuchAlgorithmException e) {
            // Fallback: just truncate
            return url.substring(0, MAX_URL_LENGTH);
        }
    }
    
    /**
     * Shorten URL for logging purposes
     */
    private String shortenForLogging(String url) {
        return url.length() > 50 ? url.substring(0, 47) + "..." : url;
    }
}

    

