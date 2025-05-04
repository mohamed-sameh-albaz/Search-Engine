package com.example.searchengine.Indexer.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentRepository;
import com.example.searchengine.Indexer.Entities.InvertedIndex;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordDocumentMetrics;
import com.example.searchengine.Indexer.Entities.WordDocumentTag;
import com.example.searchengine.Indexer.Entities.WordIdf;
import com.example.searchengine.Indexer.Repository.InvertedIndexRepository;
import com.example.searchengine.Indexer.Repository.WordDocumentMetricsRepository;
import com.example.searchengine.Indexer.Repository.WordDocumentTagRepository;
import com.example.searchengine.Indexer.Repository.WordIdfRepository;
import com.example.searchengine.Indexer.Repository.WordRepository;
import com.example.searchengine.Indexer.Repository.WordPositionRepository;

@Service
public class IndexerService {

    private final WordRepository wordRepository;
    private final DocumentRepository documentRepository;
    private final WordDocumentTagRepository wordDocumentTagRepository;
    private final InvertedIndexRepository invertedIndexRepository;
    private final WordIdfRepository wordIdfRepository;
    private final WordDocumentMetricsRepository wordDocumentMetricsRepository;
    private final WordPositionRepository wordPositionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PreIndexer preIndexer;
    
    // Add word cache to reduce database lookups
    private final Map<String, Word> wordCache = new java.util.concurrent.ConcurrentHashMap<>();

    // SQL statements for batch operations
    private static final String WORD_POSITION_INSERT = 
        "INSERT INTO word_position (word_id, doc_id, position, tag) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
    
    private static final String WORD_DOCUMENT_TAGS_INSERT =
        "INSERT INTO word_document_tags (word_id, doc_id, tag, frequency) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT (word_id, doc_id, tag) DO UPDATE SET frequency = word_document_tags.frequency + EXCLUDED.frequency";
    
    private static final String INVERTED_INDEX_INSERT =
        "INSERT INTO inverted_index (word_id, doc_id, frequency, tf, importance) VALUES (?, ?, ?, ?, ?) " +
        "ON CONFLICT (word_id, doc_id) DO UPDATE SET frequency = inverted_index.frequency + EXCLUDED.frequency, " +
        "tf = EXCLUDED.tf, importance = GREATEST(inverted_index.importance, EXCLUDED.importance)";

    @Autowired
    public IndexerService(WordRepository wordRepository, DocumentRepository documentRepository,
            WordDocumentTagRepository wordDocumentTagRepository, InvertedIndexRepository invertedIndexRepository,
            WordIdfRepository wordIdfRepository, WordDocumentMetricsRepository wordDocumentMetricsRepository,
            WordPositionRepository wordPositionRepository, PreIndexer preIndexer, JdbcTemplate jdbcTemplate) {
        this.wordRepository = wordRepository;
        this.documentRepository = documentRepository;
        this.wordDocumentTagRepository = wordDocumentTagRepository;
        this.invertedIndexRepository = invertedIndexRepository;
        this.wordIdfRepository = wordIdfRepository;
        this.wordDocumentMetricsRepository = wordDocumentMetricsRepository;
        this.wordPositionRepository = wordPositionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.preIndexer = preIndexer;
    }

    private static class WordInfo {
        Map<String, Integer> tagFrequencies = new HashMap<>();
        int totalFrequency = 0;
        Map<String, List<Integer>> tagPositions = new HashMap<>(); // Store positions for each tag

        void addFrequency(String tag, int frequency) {
            tagFrequencies.put(tag, tagFrequencies.getOrDefault(tag, 0) + frequency);
            totalFrequency += frequency;
        }
        
        void addPosition(String tag, int position) {
            if (!tagPositions.containsKey(tag)) {
                tagPositions.put(tag, new ArrayList<>());
            }
            tagPositions.get(tag).add(position);
        }
    }

    // Method to preload words into cache
    private void preloadWordCache(Set<String> wordTexts) {
        if (wordTexts.isEmpty()) return;
        
        // Clear current cache to avoid memory issues
        wordCache.clear();
        
        // Split into manageable chunks to avoid huge IN clauses
        int chunkSize = 500;
        for (int i = 0; i < wordTexts.size(); i += chunkSize) {
            List<String> chunk = new ArrayList<>(
                wordTexts).subList(i, Math.min(i + chunkSize, wordTexts.size()));
            
            // Use JDBC for better performance than JPA repository
            String placeholders = String.join(",", 
                chunk.stream().map(s -> "?").toArray(String[]::new));
            
            String query = "SELECT id, word, total_frequency FROM words WHERE word IN (" + placeholders + ")";
            
            jdbcTemplate.query(query, (rs, rowNum) -> {
                Word word = new Word();
                word.setId(rs.getLong("id"));
                word.setWord(rs.getString("word"));
                word.setTotalFrequency(rs.getLong("total_frequency"));
                wordCache.put(word.getWord(), word);
                return word;
            }, chunk.toArray());
        }
    }
    
    // Optimized word lookup/creation
    private Word getOrCreateWord(String wordText) {
        return wordCache.computeIfAbsent(wordText, text -> {
            Word word = wordRepository.findByWord(text).orElse(null);
            if (word == null) {
                Word newWord = new Word();
                newWord.setWord(text);
                newWord.setTotalFrequency(0L);
                word = wordRepository.save(newWord);
            }
            return word;
        });
    }
    
    // Batch insert word positions
    private void batchInsertWordPositions(Long wordId, Long docId, Map<String, List<Integer>> tagPositions) {
        // Prepare batch arguments
        List<Object[]> batchArgs = new ArrayList<>();
        
        for (Map.Entry<String, List<Integer>> entry : tagPositions.entrySet()) {
            String tag = entry.getKey();
            List<Integer> positions = entry.getValue();
            
            for (Integer position : positions) {
                batchArgs.add(new Object[]{wordId, docId, position, tag});
            }
        }
        
        // Execute in batches
        int batchSize = 500;
        for (int i = 0; i < batchArgs.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, batchArgs.size());
            List<Object[]> batch = batchArgs.subList(i, endIndex);
            
            jdbcTemplate.batchUpdate(WORD_POSITION_INSERT, batch);
        }
    }
    
    // Batch insert word document tags
    private void batchInsertWordDocumentTags(Long wordId, Long docId, Map<String, Integer> tagFrequencies) {
        List<Object[]> batchArgs = new ArrayList<>();
        
        for (Map.Entry<String, Integer> entry : tagFrequencies.entrySet()) {
            String tag = entry.getKey();
            int frequency = entry.getValue();
            batchArgs.add(new Object[]{wordId, docId, tag, frequency});
        }
        
        jdbcTemplate.batchUpdate(WORD_DOCUMENT_TAGS_INSERT, batchArgs);
    }

    private Map<String, WordInfo> extractWordsFromTag(Element element, String tag) {
        Map<String, WordInfo> wordInfo = new HashMap<>();
        String text = element.text();
        List<String> words = preIndexer.tokenize(text);
        words = preIndexer.removeStopWords(words);
        words = preIndexer.Stemming(words);
        
        int position = 0;
        for (String wordTxt : words) {
            WordInfo info = wordInfo.computeIfAbsent(wordTxt, w -> new WordInfo());
            info.addFrequency(tag, 1);
            info.addPosition(tag, position);
            position++;
        }
        return wordInfo;
    }

    @Transactional
    private void createInvertedIndex(String url, String HTML) {
        String cleanedHTML = preIndexer.cleanHTML(HTML);
        List<String> words = preIndexer.tokenize(cleanedHTML);
        words = preIndexer.removeStopWords(words);
        words = preIndexer.Stemming(words);
        Map<String, Long> wordFreq = new HashMap<>();
        for (String word : words) {
            wordFreq.put(word, wordFreq.getOrDefault(word, 0L) + 1L);
        }
        Document documentEntity = documentRepository.findByUrl(url).orElse(null);
        if (documentEntity != null) {
            documentEntity.setLastIndexed(LocalDateTime.now());
            documentRepository.save(documentEntity);
            documentRepository.flush();
        }

        for (Map.Entry<String, Long> entry : wordFreq.entrySet()) {
            String wordTxt = entry.getKey();
            Long freq = entry.getValue();
            Word word = wordRepository.findByWord(wordTxt).orElse(null);
            if (word == null) {
                jdbcTemplate.update("INSERT INTO words(word, total_frequency) VALUES (?, ?);", wordTxt, freq);
            } else {
                jdbcTemplate.update("UPDATE words SET total_frequency = total_frequency + ? WHERE id = ?;", freq,
                        word.getId());
            }
            jdbcTemplate.update(
                    "INSERT INTO inverted_index (word_id, doc_id, frequency) VALUES (?, ?, ?) " +
                            "ON CONFLICT (word_id, doc_id) DO UPDATE SET frequency = inverted_index.frequency + ?",
                    word.getId(), documentEntity.getId(), freq, freq);
        }
    }

    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    private void indexPage(String url, String html) {
        System.out.println("Indexing page: " + url);
        org.jsoup.nodes.Document doc = Jsoup.parse(html); // parse the html
        Document documentEntity = documentRepository.findByUrl(url).orElse(null); // check for existence
        
        if (documentEntity == null) {
            System.err.println("Document not found in database: " + url);
            return;
        }
        
        Map<String, WordInfo> pageWordInfo = new HashMap<>(); // store the page info
        
        // Get the total word count in the document for TF calculation
        String fullText = doc.text();
        List<String> allWords = preIndexer.tokenize(fullText);
        allWords = preIndexer.removeStopWords(allWords);
        allWords = preIndexer.Stemming(allWords);
        int totalWordCount = allWords.size();

        // Extract unique words for preloading
        Set<String> uniqueWords = new HashSet<>(allWords);

        // Preload existing words into cache
        preloadWordCache(uniqueWords);

        // Map to assign importance values to different HTML tags
        Map<String, Integer> tagImportance = new HashMap<>();
        tagImportance.put("title", 10); // Title has highest importance
        tagImportance.put("h1", 8);
        tagImportance.put("h2", 6);
        tagImportance.put("h3", 4);
        tagImportance.put("p", 2);
        
        // Add title tag to indexed tags
        List<String> tagsToIndex = new ArrayList<>(Arrays.asList("p", "h1", "h2", "h3"));
        tagsToIndex.add("title");

        // More efficient selection - group by tag type
        for (String tag : tagsToIndex) {
            for (org.jsoup.nodes.Element element : doc.select(tag)) {
                Map<String, WordInfo> wordInfoMap = extractWordsFromTag(element, tag);
                for (Map.Entry<String, WordInfo> entry : wordInfoMap.entrySet()) {
                    String wordText = entry.getKey();
                    WordInfo tagInfo = entry.getValue();
                    WordInfo pageInfo = pageWordInfo.computeIfAbsent(wordText, k -> new WordInfo());
                    
                    // Merge tag frequencies
                    for (Map.Entry<String, Integer> tagEntry : tagInfo.tagFrequencies.entrySet()) {
                        pageInfo.tagFrequencies.put(
                            tagEntry.getKey(),
                            pageInfo.tagFrequencies.getOrDefault(tagEntry.getKey(), 0) + tagEntry.getValue()
                        );
                    }
                    
                    // Merge position information
                    for (Map.Entry<String, List<Integer>> posEntry : tagInfo.tagPositions.entrySet()) {
                        String posTag = posEntry.getKey();
                        List<Integer> positions = posEntry.getValue();
                        pageInfo.tagPositions.computeIfAbsent(posTag, k -> new ArrayList<>()).addAll(positions);
                    }
                    
                    pageInfo.totalFrequency += tagInfo.totalFrequency;
                }
            }
        }

        // Prepare batch updates for all words at once
        List<Object[]> wordFrequencyUpdates = new ArrayList<>();
        List<Object[]> invertedIndexInserts = new ArrayList<>();
        
        for (Map.Entry<String, WordInfo> entry : pageWordInfo.entrySet()) {
            String wordText = entry.getKey();
            WordInfo info = entry.getValue();

            // Get or create word from cache
            Word word = getOrCreateWord(wordText);
            
            try {
                // Calculate TF = frequency in document / total words in document
                double tf = (double) info.totalFrequency / totalWordCount;
                
                // Determine importance - use the highest importance from all tags this word appears in
                int importance = 1; // Default importance
                for (String tag : info.tagFrequencies.keySet()) {
                    int tagImp = tagImportance.getOrDefault(tag, 1);
                    if (tagImp > importance) {
                        importance = tagImp;
                    }
                }
                
                // Add to batch updates
                wordFrequencyUpdates.add(new Object[]{info.totalFrequency, word.getId()});
                invertedIndexInserts.add(new Object[]{
                    word.getId(), documentEntity.getId(), info.totalFrequency, tf, importance
                });
                
                // Batch insert word document tags
                batchInsertWordDocumentTags(word.getId(), documentEntity.getId(), info.tagFrequencies);
                
                // Batch insert word positions
                batchInsertWordPositions(word.getId(), documentEntity.getId(), info.tagPositions);
                
            } catch (Exception e) {
                // Log error but continue processing other words
                System.err.println("Error indexing word " + wordText + " in document " + url + ": " + e.getMessage());
            }
        }
        
        // Execute batch updates
        if (!wordFrequencyUpdates.isEmpty()) {
            jdbcTemplate.batchUpdate(
                "UPDATE words SET total_frequency = total_frequency + ? WHERE id = ?",
                wordFrequencyUpdates
            );
        }
        
        if (!invertedIndexInserts.isEmpty()) {
            int batchSize = 500;
            for (int i = 0; i < invertedIndexInserts.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, invertedIndexInserts.size());
                List<Object[]> batch = invertedIndexInserts.subList(i, endIndex);
                jdbcTemplate.batchUpdate(INVERTED_INDEX_INSERT, batch);
            }
        }
        
        // Update document as indexed
        documentEntity.setLastIndexed(LocalDateTime.now());
        documentRepository.save(documentEntity);
    }

    /**
     * Main method to build the inverted index
     */
    public void buildIndex(Map<String, String> documents) {
        if (documents.isEmpty()) {
            System.out.println("No documents to index");
            return;
        }
        
        System.out.println("Indexing " + documents.size() + " documents in batches with multi-threading");
        
        // Clear any existing caches
        CacheHelper.clearInvertedIndexCache();
        wordCache.clear();
        
        final int BATCH_SIZE = 20; // Process 20 documents per batch
        final int NUM_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 8); // Use available processors but cap at 8
        
        // Create batches of documents
        List<Map.Entry<String, String>> docEntries = new ArrayList<>(documents.entrySet());
        List<List<Map.Entry<String, String>>> batches = splitIntoBatches(docEntries, BATCH_SIZE);
        
        System.out.println("Split into " + batches.size() + " batches with " + NUM_THREADS + " threads");
        
        // Track progress
        AtomicInteger processedCount = new AtomicInteger(0);
        int totalDocuments = documents.size();
        
        // Process each batch
        for (List<Map.Entry<String, String>> batch : batches) {
            processBatchWithThreads(batch, NUM_THREADS);
            
            // Update progress after each batch
            int currentProcessed = processedCount.addAndGet(batch.size());
            
            // Use static method instead of direct controller reference
            com.example.searchengine.controllers.ReindexController.updateIndexingProgress(currentProcessed);
            
            System.out.printf("Processed %d/%d documents (%.1f%%)\n", 
                currentProcessed, totalDocuments, (100.0 * currentProcessed / totalDocuments));
        }
        
        // Calculate IDF after all documents have been processed
        System.out.println("All documents indexed. Calculating IDF values...");
        calculateAllIdfValues();
        
        System.out.println("Indexing completed successfully");
    }

    /**
     * Process a batch of documents using multiple threads
     */
    private void processBatchWithThreads(List<Map.Entry<String, String>> batch, int numThreads) {
        List<List<Map.Entry<String, String>>> threadBatches = splitIntoBatches(batch, numThreads);
        List<Thread> threads = new ArrayList<>();
        
        for (List<Map.Entry<String, String>> threadBatch : threadBatches) {
            Thread thread = new Thread(() -> {
                for (Map.Entry<String, String> entry : threadBatch) {
                    try {
                        indexPage(entry.getKey(), entry.getValue());
                    } catch (Exception e) {
                        System.err.println("Error processing document " + entry.getKey() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread interrupted: " + e.getMessage());
            }
        }
        
        // Commit changes to database after each batch
        System.out.println("Committing batch to database...");
        // If using JdbcTemplate, you might need to flush manually if there's caching involved
        // Otherwise, Spring Data JPA should auto-commit after each repository save
    }

    /**
     * Split a list into approximately equal-sized batches
     */
    private <T> List<List<T>> splitIntoBatches(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        
        if (list.isEmpty()) {
            return batches;
        }
        
        int size = list.size();
        int start = 0;
        
        while (start < size) {
            int end = Math.min(start + batchSize, size);
            batches.add(new ArrayList<>(list.subList(start, end)));
            start = end;
        }
        
        return batches;
    }

    /**
     * Calculate IDF values for all words after indexing is complete
     */
    public void calculateAllIdfValues() {
        System.out.println("Calculating IDF values for all words...");
        
        long totalDocuments = documentRepository.count();
        if (totalDocuments == 0) {
            System.out.println("No documents found, skipping IDF calculation");
            return;
        }
        
        // Get all words
        List<Word> allWords = wordRepository.findAll();
        
        // Process in batches to avoid memory issues
        final int BATCH_SIZE = 500;
        List<List<Word>> batches = splitIntoBatches(allWords, BATCH_SIZE);
        
        for (List<Word> batch : batches) {
            for (Word word : batch) {
                try {
                    // Count documents containing this word using SQL query instead of repository method
                    long docCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM inverted_index WHERE word_id = ?", 
                        Long.class,
                        word.getId()
                    );
                    
                    // Calculate IDF: log(totalDocs/(docCount+1))
                    // Adding 1 to avoid division by zero
                    double idf = Math.log10((double) totalDocuments / (docCount + 1));
                    
                    // Update or create WordIdf entry
                    WordIdf wordIdf = wordIdfRepository.findByWord(word)
                            .orElse(new WordIdf());
                    
                    wordIdf.setWord(word);
                    wordIdf.setIdfValue(idf);
                    wordIdf.setDocumentFrequency(docCount);
                    wordIdf.setTotalDocuments(totalDocuments);
                    
                    wordIdfRepository.save(wordIdf);
                } catch (Exception e) {
                    System.err.println("Error calculating IDF for word " + word.getWord() + ": " + e.getMessage());
                }
            }
        }
        
        System.out.println("IDF calculation completed for " + allWords.size() + " words");
    }

    public Map<String, Map<String, Object>> getIndex() {
        Map<String, Map<String, Object>> index = new HashMap<>();
        int pageSize = 10;
        int page = 0;
        boolean hasMore = true;

        while (hasMore) {
            // pagination
            List<Word> words = wordRepository.findAll(PageRequest.of(page, pageSize)).getContent();
            if (words.isEmpty()) {
                hasMore = false;
                continue;
            }

            for (Word word : words) {
                List<InvertedIndex> mappings = invertedIndexRepository.findByWord(word,
                        PageRequest.of(0, Integer.MAX_VALUE));
                List<WordDocumentTag> tags = wordDocumentTagRepository.findByWord(word,
                        PageRequest.of(0, Integer.MAX_VALUE));

                Map<String, Object> wordDetails = new HashMap<>();
                wordDetails.put("totalFrequency", word.getTotalFrequency());

                List<Map<String, Object>> documentDetails = new ArrayList<>();
                for (InvertedIndex mapping : mappings) {
                    Map<String, Object> docDetail = new HashMap<>();
                    docDetail.put("url", mapping.getDocument().getUrl());
                    docDetail.put("title", mapping.getDocument().getTitle());
                    docDetail.put("frequency", mapping.getFrequency());

                    List<Map<String, Object>> tagDetails = new ArrayList<>();
                    for (WordDocumentTag tag : tags) {
                        if (tag.getDocument().getId().equals(mapping.getDocument().getId())) {
                            Map<String, Object> tagDetail = new HashMap<>();
                            tagDetail.put("tag", tag.getTag());
                            tagDetail.put("frequency", tag.getFrequency());
                            tagDetails.add(tagDetail);
                        }
                    }
                    docDetail.put("tags", tagDetails);
                    documentDetails.add(docDetail);
                }

                wordDetails.put("documents", documentDetails);
                if (!documentDetails.isEmpty()) {
                    index.put(word.getWord(), wordDetails);
                }
            }
            page++;
        }
        return index;
    }

    // word, (doc_id, freqOfWord)
    public Map<String, Map<Long, Integer>> getInvertedIndex() {
        // Check if we already have the index in memory cache
        if (CacheHelper.getInvertedIndexCache() != null) {
            System.out.println("Using cached inverted index");
            return CacheHelper.getInvertedIndexCache();
        }
        
        System.out.println("Building inverted index - using parallel processing for faster startup");
        
        // Use ConcurrentHashMap for thread safety
        Map<String, Map<Long, Integer>> index = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        try {
            // First, get the total count of words and use a larger page size for better throughput
            long totalWords = wordRepository.count();
            System.out.println("Total words to process: " + totalWords);
            
            // Calculate optimal thread count based on CPU cores
            int numThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
            System.out.println("Using " + numThreads + " threads for parallel index building");
            
            // If there are no words, return an empty index
            if (totalWords == 0) {
                System.out.println("No words found in the database, returning empty index");
                CacheHelper.setInvertedIndexCache(index);
                return index;
            }
            
            // Create an execution service for parallel processing
            java.util.concurrent.ExecutorService executor = 
                java.util.concurrent.Executors.newFixedThreadPool(numThreads);
            java.util.concurrent.CountDownLatch completionLatch = 
                new java.util.concurrent.CountDownLatch(numThreads);
            
            // For better performance, process all words at once if under a threshold,
            // otherwise divide them into chunks
            int pageSize = 2000; // Larger page size for each worker
            
            // Get all words at once if under 10,000, otherwise use paging
            if (totalWords < 10000) {
                // Process all words in a single load
                List<Word> allWords = wordRepository.findAll();
                System.out.println("Processing all " + allWords.size() + " words at once");
                
                // Split into chunks for parallel processing
                List<List<Word>> chunks = splitIntoChunks(allWords, numThreads);
                for (int i = 0; i < chunks.size(); i++) {
                    final int chunkIndex = i;
                    List<Word> chunk = chunks.get(i);
                    executor.submit(() -> {
                        try {
                            System.out.println("Thread " + chunkIndex + " starting to process " + chunk.size() + " words");
                            processWordChunk(chunk, index);
                            int current = processedCount.addAndGet(chunk.size());
                            System.out.println("Thread " + chunkIndex + " completed chunk: " + 
                                              getProgressBar(current, totalWords, 30));
                        } catch (Exception e) {
                            System.err.println("Error processing word chunk: " + e.getMessage());
                        } finally {
                            completionLatch.countDown();
                        }
                    });
                }
            } else {
                // Determine number of pages per thread
                int totalPages = (int) Math.ceil((double) totalWords / pageSize);
                int pagesPerThread = Math.max(1, totalPages / numThreads);
                
                // Submit tasks for each worker
                for (int threadIdx = 0; threadIdx < numThreads; threadIdx++) {
                    final int workerIdx = threadIdx;
                    final int startPage = threadIdx * pagesPerThread;
                    final int endPage = (threadIdx == numThreads - 1) ? 
                                        totalPages : Math.min(totalPages, (threadIdx + 1) * pagesPerThread);
                    
                    executor.submit(() -> {
                        try {
                            System.out.println("Worker " + workerIdx + " starting to process pages " + 
                                              startPage + " to " + (endPage-1));
                            for (int page = startPage; page < endPage; page++) {
                                List<Word> pageWords = wordRepository.findAll(
                                    PageRequest.of(page, pageSize)).getContent();
                                
                                System.out.println("Processing " + pageWords.size() + " words (page " + page + ")");
                                processWordChunk(pageWords, index);
                                
                                int current = processedCount.addAndGet(pageWords.size());
                                System.out.println("Completed page " + page + ": " + 
                                                 getProgressBar(current, totalWords, 30));
                            }
                        } catch (Exception e) {
                            System.err.println("Error in worker " + workerIdx + " processing pages " + 
                                               startPage + " to " + endPage + ": " + e.getMessage());
                        } finally {
                            completionLatch.countDown();
                        }
                    });
                }
            }
            
            // Wait for all threads to complete
            try {
                System.out.println("Waiting for all threads to finish building the index...");
                completionLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for index building: " + e.getMessage());
            }
            
            // Shutdown the executor
            executor.shutdown();
            
            // Cache the index for future use
            CacheHelper.setInvertedIndexCache(index);
            
            // Calculate memory and document stats
            long totalDocuments = documentRepository.count();
            long totalWordDocPairs = index.values().stream()
                .mapToLong(map -> map.size())
                .sum();
            
            Runtime runtime = Runtime.getRuntime();
            long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            
            System.out.println("\n------------------------------------------------------------");
            System.out.println("Inverted index generation completed successfully:");
            System.out.println("  - Unique words in index: " + totalWords);
            System.out.println("  - Total documents: " + totalDocuments);
            System.out.println("  - Total word-document pairs: " + totalWordDocPairs);
            System.out.println("  - Used memory: " + usedMemoryMB + " MB");
            System.out.println("------------------------------------------------------------\n");
                               
        } catch (Exception e) {
            System.err.println("Error generating inverted index: " + e.getMessage());
            e.printStackTrace();
        }
        
        return index;
    }

    /**
     * Helper class for caching the inverted index
     */
    private static class CacheHelper {
        private static Map<String, Map<Long, Integer>> invertedIndexCache;
        
        public static Map<String, Map<Long, Integer>> getInvertedIndexCache() {
            return invertedIndexCache;
        }
        
        public static void setInvertedIndexCache(Map<String, Map<Long, Integer>> index) {
            invertedIndexCache = index;
        }
        
        public static void clearInvertedIndexCache() {
            invertedIndexCache = null;
        }
    }

    /**
     * Process a chunk of words in parallel with optimized SQL queries
     */
    private void processWordChunk(List<Word> wordChunk, Map<String, Map<Long, Integer>> index) {
        for (Word word : wordChunk) {
            try {
                String wordText = word.getWord();
                Long wordId = word.getId();
                
                // Process in smaller batches to avoid memory issues but don't limit total results
                Map<Long, Integer> docFrequencies = new java.util.concurrent.ConcurrentHashMap<>();
                int batchSize = 200; // Larger batch size for better performance
                int offset = 0;
                boolean hasMore = true;
                
                while (hasMore) {
                    // Use pagination to avoid loading everything at once
                    List<Object[]> batch = jdbcTemplate.query(
                        "SELECT doc_id, frequency FROM inverted_index WHERE word_id = ? LIMIT ? OFFSET ?",
                        (rs, rowNum) -> new Object[] {
                            rs.getLong("doc_id"),
                            rs.getInt("frequency")
                        },
                        wordId, batchSize, offset
                    );
                    
                    if (batch.isEmpty()) {
                        hasMore = false;
                    } else {
                        // Add document frequencies to the map
                        for (Object[] mapping : batch) {
                            Long docId = (Long) mapping[0];
                            Integer frequency = (Integer) mapping[1];
                            docFrequencies.put(docId, frequency);
                        }
                        
                        // Move to next batch
                        offset += batchSize;
                    }
                }
                
                // Add the word to the index if it has any documents
                if (!docFrequencies.isEmpty()) {
                    index.put(wordText, docFrequencies);
                }
            } catch (Exception e) {
                System.err.println("Error processing word '" + word.getWord() + "': " + e.getMessage());
            }
        }
    }

    /**
     * Split a list into approximately equal-sized chunks
     */
    private <T> List<List<T>> splitIntoChunks(List<T> list, int numChunks) {
        List<List<T>> chunks = new ArrayList<>();
        int size = list.size();
        
        // Return single empty chunk if list is empty
        if (size == 0) {
            chunks.add(new ArrayList<>());
            return chunks;
        }
        
        // Adjust number of chunks if list is smaller
        numChunks = Math.min(numChunks, size);
        
        // Calculate chunk size
        int chunkSize = size / numChunks;
        int remainder = size % numChunks;
        
        int startIndex = 0;
        for (int i = 0; i < numChunks; i++) {
            int currentChunkSize = chunkSize + (i < remainder ? 1 : 0);
            if (currentChunkSize > 0) {
                int endIndex = Math.min(startIndex + currentChunkSize, size);
                chunks.add(new ArrayList<>(list.subList(startIndex, endIndex)));
                startIndex = endIndex;
            }
        }
        
        return chunks;
    }

    // (doc_id, #words)
    public Map<Long, Long> getDocumentCnt() {
        Map<Long, Long> docCnt = new HashMap<>();
        int pageSize = 500; // Increased batch size for better performance
        int page = 0;
        boolean hasMore = true;
        
        while (hasMore) {
            List<Document> docs = documentRepository.findAll(PageRequest.of(page, pageSize)).getContent();
            if (docs.isEmpty()) {
                hasMore = false;
                continue;
            }
            
            // Process the batch in parallel for better performance
            Map<Long, Long> batchCounts = docs.parallelStream()
                .collect(Collectors.toMap(
                    Document::getId,
                    doc -> {
                        String cleanedHTML = preIndexer.cleanHTML(doc.getContent());
                        List<String> words = preIndexer.tokenize(cleanedHTML);
                        words = preIndexer.removeStopWords(words);
                        words = preIndexer.Stemming(words);
                        return (long) words.size();
                    }
                ));
            
            // Add batch results to the overall map
            docCnt.putAll(batchCounts);
            
            page++;
            
            // Help GC between batches
            System.gc();
        }
        
        return docCnt;
    }

    // This function creates a frequency array from the document count map.
    // The index is the document ID, and the value is the word count.
    public long[] getDocumentCntArray() {
        Map<Long, Long> docCnt = getDocumentCnt();
        if (docCnt == null || docCnt.isEmpty()) {
            return new long[0];
        }
        long maxId = Collections.max(docCnt.keySet());
        long[] freqArray = new long[(int) (maxId + 1)];

        for (Map.Entry<Long, Long> entry : docCnt.entrySet()) {
            freqArray[entry.getKey().intValue()] = entry.getValue();
        }
        return freqArray;
    }

    /**
     * Returns a string with a progress bar
     */
    private String getProgressBar(long current, long total, int barLength) {
        float percent = (float) current / total;
        int completedLength = Math.round(barLength * percent);
        
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barLength; i++) {
            bar.append(i < completedLength ? "=" : " ");
        }
        bar.append("] ");
        bar.append(String.format("%.1f%%", percent * 100));
        bar.append(String.format(" (%d/%d)", current, total));
        
        return bar.toString();
    }

    /**
     * Compute and store IDF values for all words in the index
     * These values are used for faster relevance ranking during search
     */
    @Transactional
    @Async
    public void computeAndStoreMetrics() {
        System.out.println("Starting metrics computation for faster search relevance...");
        long startTime = System.currentTimeMillis();
        
        // Get total document count
        long totalDocuments = documentRepository.count();
        if (totalDocuments == 0) {
            System.out.println("No documents found, skipping metrics computation");
            return;
        }
        
        System.out.println("Computing metrics for " + totalDocuments + " documents");
        
        // First, compute and store IDF values for all words
        computeAndStoreIdfValues(totalDocuments);
        
        // Then, compute and store TF and TF-IDF values for all word-document pairs
        computeAndStoreTfIdfValues(totalDocuments);
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("------------------------------------------------------------");
        System.out.println("Metrics computation completed in " + (totalTime / 1000.0) + " seconds");
        System.out.println("------------------------------------------------------------");
    }
    
    /**
     * Compute and store IDF values for all words
     */
    @Transactional
    private void computeAndStoreIdfValues(long totalDocuments) {
        System.out.println("Computing IDF values for all words...");
        long startTime = System.currentTimeMillis();
        
        // Process words in batches to avoid memory issues
        int pageSize = 1000;
        int page = 0;
        boolean hasMore = true;
        int processedCount = 0;
        
        while (hasMore) {
            List<Word> wordBatch = wordRepository.findAll(PageRequest.of(page, pageSize)).getContent();
            if (wordBatch.isEmpty()) {
                hasMore = false;
                continue;
            }
            
            for (Word word : wordBatch) {
                try {
                    // Count documents containing this word
                    long docFrequency = jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT doc_id) FROM inverted_index WHERE word_id = ?", 
                        Long.class, 
                        word.getId());
                    
                    if (docFrequency == 0) {
                        // Skip words that don't appear in any document
                        continue;
                    }
                    
                    // Calculate IDF with smoothing to avoid division by zero and extreme values
                    // IDF = log((1 + N) / (1 + df)) + 1
                    double idf = Math.log((1.0 + totalDocuments) / (1.0 + docFrequency)) + 1.0;
                    
                    // Store or update IDF value
                    Optional<WordIdf> existingIdf = wordIdfRepository.findByWord(word);
                    if (existingIdf.isPresent()) {
                        WordIdf wordIdf = existingIdf.get();
                        wordIdf.setIdfValue(idf);
                        wordIdf.setDocumentFrequency(docFrequency);
                        wordIdf.setTotalDocuments(totalDocuments);
                        wordIdfRepository.save(wordIdf);
                    } else {
                        WordIdf wordIdf = new WordIdf();
                        wordIdf.setWord(word);
                        wordIdf.setIdfValue(idf);
                        wordIdf.setDocumentFrequency(docFrequency);
                        wordIdf.setTotalDocuments(totalDocuments);
                        wordIdfRepository.save(wordIdf);
                    }
                    
                    processedCount++;
                    
                    // Show progress every 1000 words
                    if (processedCount % 1000 == 0) {
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        System.out.printf("Processed IDF for %d words - Elapsed time: %.2fs\n", 
                            processedCount, elapsedTime / 1000.0);
                    }
                } catch (Exception e) {
                    System.err.println("Error computing IDF for word " + word.getWord() + ": " + e.getMessage());
                }
            }
            
            page++;
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("IDF computation completed for " + processedCount + " words in " + (totalTime / 1000.0) + " seconds");
    }
    
    /**
     * Compute and store TF and TF-IDF values for all word-document pairs
     */
    @Transactional
    private void computeAndStoreTfIdfValues(long totalDocuments) {
        System.out.println("Computing TF-IDF scores for all word-document pairs...");
        long startTime = System.currentTimeMillis();
        
        // Get document length information (total terms per document)
        Map<Long, Long> docLengths = getDocumentCnt();
        
        // Calculate average document length for BM25-style normalization
        double avgDocLength = docLengths.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(500.0); // fallback to 500 if no data
        
        // Process in batches by word to avoid memory issues
        int pageSize = 100;
        int page = 0;
        boolean hasMore = true;
        AtomicLong processedPairs = new AtomicLong(0);
        
        // BM25 parameters
        final double k1 = 1.2; // Controls term frequency scaling
        final double b = 0.75; // Controls document length normalization
        
        while (hasMore) {
            List<Word> wordBatch = wordRepository.findAll(PageRequest.of(page, pageSize)).getContent();
            if (wordBatch.isEmpty()) {
                hasMore = false;
                continue;
            }
            
            for (Word word : wordBatch) {
                try {
                    // Get IDF for this word
                    Optional<WordIdf> wordIdfOpt = wordIdfRepository.findByWord(word);
                    if (!wordIdfOpt.isPresent()) {
                        continue; // Skip if IDF not computed
                    }
                    
                    double idf = wordIdfOpt.get().getIdfValue();
                    
                    // Get all documents containing this word
                    List<InvertedIndex> indexEntries = invertedIndexRepository.findByWordId(word.getId());
                    
                    // Store maximum TF-IDF for normalization
                    double maxTfIdf = 0.0;
                    
                    // First pass: calculate TF-IDF scores and find max
                    List<WordDocumentMetrics> metricsToSave = new ArrayList<>();
                    
                    for (InvertedIndex entry : indexEntries) {
                        Long docId = entry.getDocument().getId();
                        Integer frequency = entry.getFrequency();
                        
                        // Get document length, use the avgDocLength as a default if not found
                        Long docLength = docLengths.containsKey(docId) ? docLengths.get(docId) : (long) avgDocLength;
                        
                        // Calculate normalized document length for BM25
                        double normDocLength = docLength / avgDocLength;
                        
                        // Calculate BM25-style term frequency component
                        double tf = ((double) frequency * (k1 + 1)) / 
                                  (frequency + k1 * (1 - b + b * normDocLength));
                        
                        // Calculate TF-IDF score
                        double tfIdf = tf * idf;
                        
                        // Keep track of maximum TF-IDF
                        maxTfIdf = Math.max(maxTfIdf, tfIdf);
                        
                        WordDocumentMetrics metrics = new WordDocumentMetrics();
                        metrics.setWord(word);
                        metrics.setDocument(entry.getDocument());
                        metrics.setFrequency(frequency);
                        metrics.setTermFrequency(tf);
                        metrics.setTfIdfScore(tfIdf);
                        metrics.setNormalizedScore(0.0); // Will be set in second pass
                        
                        metricsToSave.add(metrics);
                    }
                    
                    // Second pass: normalize scores and save
                    for (WordDocumentMetrics metrics : metricsToSave) {
                        // Normalize to [0,1] range if max > 0
                        if (maxTfIdf > 0) {
                            metrics.setNormalizedScore(metrics.getTfIdfScore() / maxTfIdf);
                        } else {
                            metrics.setNormalizedScore(0.0);
                        }
                        
                        // Save to database (use JDBC batch operations for better performance)
                        try {
                            // Check if entry already exists
                            Optional<WordDocumentMetrics> existingMetrics = 
                                wordDocumentMetricsRepository.findByWordAndDocument(metrics.getWord(), metrics.getDocument());
                            
                            if (existingMetrics.isPresent()) {
                                // Update existing entry
                                WordDocumentMetrics existing = existingMetrics.get();
                                existing.setFrequency(metrics.getFrequency());
                                existing.setTermFrequency(metrics.getTermFrequency());
                                existing.setTfIdfScore(metrics.getTfIdfScore());
                                existing.setNormalizedScore(metrics.getNormalizedScore());
                                wordDocumentMetricsRepository.save(existing);
                            } else {
                                // Insert new entry
                                wordDocumentMetricsRepository.save(metrics);
                            }
                            
                            processedPairs.incrementAndGet();
                        } catch (Exception e) {
                            System.err.println("Error saving metrics: " + e.getMessage());
                        }
                    }
                    
                    // Show progress every 50 words
                    if (processedPairs.get() % 1000 == 0) {
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        System.out.printf("Processed TF-IDF for %d word-document pairs - Elapsed time: %.2fs\n", 
                            processedPairs.get(), elapsedTime / 1000.0);
                    }
                } catch (Exception e) {
                    System.err.println("Error computing TF-IDF for word " + word.getWord() + ": " + e.getMessage());
                }
            }
            
            page++;
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("TF-IDF computation completed for " + processedPairs.get() + 
                " word-document pairs in " + (totalTime / 1000.0) + " seconds");
    }

}

