package com.example.searchengine;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
     * Process documents in small batches to avoid memory issues.
     * Commented out by default - use the /reindex endpoint instead.
     */
    // private void processDocumentsInBatches() {
    //     int pageSize = 20;
    //     int page = 0;
    //     boolean hasMore = true;
    //     
    //     while (hasMore) {
    //         Page<Document> documentPage = documentRepository.findAll(PageRequest.of(page, pageSize));
    //         if (documentPage.isEmpty()) {
    //             hasMore = false;
    //             continue;
    //         }
    //         
    //         Map<String, String> batchToIndex = new HashMap<>();
    //         documentPage.forEach(doc -> {
    //             if (doc.getUrl() != null && doc.getContent() != null) {
    //                 batchToIndex.put(doc.getUrl(), doc.getContent());
    //             }
    //         });
    //         
    //         if (!batchToIndex.isEmpty()) {
    //             indexerService.buildIndex(batchToIndex);
    //         }
    //         
    //         batchToIndex.clear();
    //         page++;
    //         System.gc(); // Suggest garbage collection
    //     }
    // }

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
