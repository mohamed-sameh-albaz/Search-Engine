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
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import java.util.Arrays;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
             indexerService.buildIndex(documentsToIndex);
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

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.setAllowedOrigins(Arrays.asList("http://localhost:3000"));
        corsConfiguration.setAllowedHeaders(Arrays.asList("Origin", "Access-Control-Allow-Origin", "Content-Type",
                "Accept", "Authorization", "Origin, Accept", "X-Requested-With",
                "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        corsConfiguration.setExposedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Authorization",
                "Access-Control-Allow-Origin", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials"));
        corsConfiguration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        UrlBasedCorsConfigurationSource urlBasedCorsConfigurationSource = new UrlBasedCorsConfigurationSource();
        urlBasedCorsConfigurationSource.registerCorsConfiguration("/**", corsConfiguration);
        return new CorsFilter(urlBasedCorsConfigurationSource);
    }
}
