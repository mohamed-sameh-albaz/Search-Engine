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
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
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
        System.out.println("Starting search engine initialization...");
        
        // Process documents in small batches
        System.out.println("Processing documents in small batches to avoid out of memory errors...");
        // Commenting out automatic indexing at startup to prevent memory issues
        // processDocumentsInBatches();
        System.out.println("Skipping document indexing at startup. Use /reindex endpoint to manually start indexing.");
        
        System.out.println("Search engine initialization complete!");
        System.out.println("Search engine ready for use.");
    }

    /**
     * Process documents in small batches to avoid memory issues
     */
    private void processDocumentsInBatches() {
        int pageSize = 20; // Small batch size to avoid memory issues
        int page = 0;
        boolean hasMore = true;
        int totalProcessed = 0;
        
        System.out.println("Starting document processing...");
        long startTime = System.currentTimeMillis();
        
        while (hasMore) {
            Page<Document> documentPage = documentRepository.findAll(PageRequest.of(page, pageSize));
            
            if (documentPage.isEmpty()) {
                hasMore = false;
                continue;
            }
            
            // Build a batch map of documents to index
            Map<String, String> batchToIndex = new HashMap<>();
            for (Document doc : documentPage.getContent()) {
                if (doc.getUrl() != null && doc.getContent() != null) {
                    batchToIndex.put(doc.getUrl(), doc.getContent());
                }
            }
            
            if (!batchToIndex.isEmpty()) {
                // Index this batch immediately
                System.out.println("Indexing batch " + (page + 1) + " with " + batchToIndex.size() + " documents...");
                indexerService.buildIndex(batchToIndex);
                
                totalProcessed += batchToIndex.size();
                long elapsedSecs = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("Progress: " + totalProcessed + " documents processed in " + elapsedSecs + " seconds");
            }
            
            // Clear memory and move to next page
            batchToIndex.clear();
            page++;
            
            // Small pause to allow GC to run if needed
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        long totalTime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("Document processing complete! Processed " + totalProcessed + 
                           " documents in " + totalTime + " seconds");
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
