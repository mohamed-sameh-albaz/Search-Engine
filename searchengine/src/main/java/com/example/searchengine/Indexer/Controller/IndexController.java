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
}