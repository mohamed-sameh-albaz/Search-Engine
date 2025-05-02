package com.example.searchengine;

import com.example.searchengine.Crawler.Entities.Document;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.searchengine.Crawler.Repository.DocumentRepository;
import com.example.searchengine.Indexer.Service.DatabaseMaintenanceService;
import com.example.searchengine.Indexer.Service.IndexerService;
import com.example.searchengine.Indexer.Service.PreIndexer;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.transaction.Transactional;

@SpringBootApplication
public class SearchengineApplication implements CommandLineRunner {

    @Autowired
    private IndexerService indexerService;

    @Autowired
    private DatabaseMaintenanceService maintenanceService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private PreIndexer preIndexer;

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));
        SpringApplication.run(SearchengineApplication.class, args);
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        Map<String, String> documentsToIndex = fetchDocumentsForIndexing();
        // maintenanceService.vacuumDatabase();
        if (!documentsToIndex.isEmpty()) {
            System.out.println("Re-indexing " + documentsToIndex.size() + "documents...");
            // indexerService.buildIndex(documentsToIndex);
        } else {
            System.out.println("No documents found to re-index.");
        }
    }

    private Map<String, String> fetchDocumentsForIndexing() {
        Map<String, String> documentsToIndex = new HashMap<>();
        int pageSize = 20;
        int page = 0;
        boolean hasMore = true;

        // while (hasMore) {
        Page<Document> documentPage = documentRepository.findAll(PageRequest.of(page,
                pageSize));
        for (Document doc : documentPage.getContent()) {
            if (doc.getUrl() != null && doc.getContent() != null) {
                documentsToIndex.put(doc.getUrl(), doc.getContent());
            }
        }
        hasMore = documentPage.hasNext();
        page++;
        System.out.println(documentsToIndex.size());
        // }

        return documentsToIndex;
    }
}
