package com.example.searchengine.Indexer.Controller;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentRepository;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Repository.WordRepository;
import com.example.searchengine.Indexer.Service.IndexerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.HashMap;
import org.springframework.data.domain.Page;

@RestController
public class IndexController {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private IndexerService indexService;

    @Autowired
    WordRepository wordRepository;

    @GetMapping("/index")
    public Map<String, Map<String, Object>> getIndex() {
        return indexService.getIndex();
    }

    @GetMapping("/")
    public List<Word> getWords() {
        return wordRepository.findAll(PageRequest.of(0, 50)).getContent();
    }

    @PostMapping("/word")
    public Word postMethodName(@RequestBody Word word) {
        return wordRepository.save(word);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex(@RequestParam(required = false) List<String> urls) {
        Map<String, Object> response = new HashMap<>();
        Map<String, String> documentsToIndex = new HashMap<>();
        
        if (urls != null && !urls.isEmpty()) {
            // Reindex specific URLs
            for (String url : urls) {
                Document doc = documentRepository.findByUrl(url).orElse(null);
                if (doc != null && doc.getContent() != null) {
                    documentsToIndex.put(doc.getUrl(), doc.getContent());
                }
            }
            response.put("message", "Reindexing " + documentsToIndex.size() + " specific documents");
        } else {
            // Reindex all documents
            Page<Document> documentPage = documentRepository.findAll(PageRequest.of(0, 100));
            for (Document doc : documentPage.getContent()) {
                if (doc.getUrl() != null && doc.getContent() != null) {
                    documentsToIndex.put(doc.getUrl(), doc.getContent());
                }
            }
            response.put("message", "Reindexing " + documentsToIndex.size() + " documents (first batch)");
        }
        
        if (!documentsToIndex.isEmpty()) {
            indexService.buildIndex(documentsToIndex);
            response.put("status", "success");
        } else {
            response.put("status", "no documents found");
        }
        
        return response;
    }

    @GetMapping("/invertedIndex")
    public Map<String, Map<Long, Integer>> getMethodName() {
        return indexService.getInvertedIndex();
    }

    @GetMapping("/documentWords")
    public Map<Long, Long> getDocumentWordsCnt() {
        return indexService.getDocumentCnt();
    }

    @GetMapping("/indexStatus")
    public Map<String, Object> getIndexStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // Get word count
        long wordCount = wordRepository.count();
        status.put("wordCount", wordCount);
        
        // Get document count
        long documentCount = documentRepository.count();
        status.put("documentCount", documentCount);
        
        // Get sample of recent words
        List<Word> recentWords = wordRepository.findAll(PageRequest.of(0, 10, 
                org.springframework.data.domain.Sort.by("id").descending())).getContent();
        
        status.put("recentWords", recentWords.stream()
                .map(Word::getWord)
                .collect(java.util.stream.Collectors.toList()));
                
        return status;
    }

}