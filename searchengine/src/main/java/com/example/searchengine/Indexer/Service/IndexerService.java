package com.example.searchengine.Indexer.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.searchengine.Crawler.Entities.Document;
import com.example.searchengine.Crawler.Repository.DocumentRepository;
import com.example.searchengine.Indexer.Entities.InvertedIndex;
import com.example.searchengine.Indexer.Entities.Word;
import com.example.searchengine.Indexer.Entities.WordDocumentTag;
import com.example.searchengine.Indexer.Repository.InvertedIndexRepository;
import com.example.searchengine.Indexer.Repository.WordDocumentTagRepository;
import com.example.searchengine.Indexer.Repository.WordRepository;

@Service
public class IndexerService {

    private final WordRepository wordRepository;
    private final DocumentRepository documentRepository;
    private final WordDocumentTagRepository wordDocumentTagRepository;
    private final InvertedIndexRepository invertedIndexRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PreIndexer preIndexer;
    private static final List<String> TAGS_TO_INDEX = Arrays.asList("p", "h1", "h2", "h3");

    @Autowired
    public IndexerService(WordRepository wordRepository, DocumentRepository documentRepository,
            WordDocumentTagRepository wordDocumentTagRepository, InvertedIndexRepository invertedIndexRepository,
            PreIndexer preIndexer, JdbcTemplate jdbcTemplate) {
        this.wordRepository = wordRepository;
        this.documentRepository = documentRepository;
        this.wordDocumentTagRepository = wordDocumentTagRepository;
        this.invertedIndexRepository = invertedIndexRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.preIndexer = preIndexer;
    }

    private static class WordInfo {
        Map<String, Integer> tagFrequencies = new HashMap<>();
        int totalFrequency = 0;

        void addFrequency(String tag, int frequency) {
            tagFrequencies.put(tag, tagFrequencies.getOrDefault(tag, 0) + frequency);
            totalFrequency += frequency;
        }
    }

    private Map<String, WordInfo> extractWordsFromTag(Element element, String tag) {
        Map<String, WordInfo> wordInfo = new HashMap<>();
        String text = element.text();
        // text = text.replaceAll("[^a-zA-Z]", " ");
        // String[] words = text.split("\\s+");
        List<String> words = preIndexer.tokenize(text);
        words = preIndexer.removeStopWords(words);
        words = preIndexer.Stemming(words);
        for (String word : words) {
            WordInfo info = wordInfo.computeIfAbsent(word, k -> new WordInfo());
            info.addFrequency(tag, 1);
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
        System.out.println("hello from the indexer");
        org.jsoup.nodes.Document doc = Jsoup.parse(html); // parse the html
        Document documentEntity = documentRepository.findByUrl(url).orElse(null); // check for existence
        Map<String, WordInfo> pageWordInfo = new HashMap<>(); // store the page info

        for (String tag : TAGS_TO_INDEX) {
            for (org.jsoup.nodes.Element element : doc.select(tag)) { // for each element in the html document
                Map<String, WordInfo> wordInfoMap = extractWordsFromTag(element, tag);
                for (Map.Entry<String, WordInfo> entry : wordInfoMap.entrySet()) { // for each word in the tag
                    String wordText = entry.getKey();
                    WordInfo tagInfo = entry.getValue();
                    WordInfo pageInfo = pageWordInfo.computeIfAbsent(wordText, k -> new WordInfo());
                    for (Map.Entry<String, Integer> tagEntry : tagInfo.tagFrequencies.entrySet()) {
                        pageInfo.tagFrequencies.put(tagEntry.getKey(),
                                pageInfo.tagFrequencies.getOrDefault(tagEntry.getKey(), 0)
                                        + tagEntry.getValue());
                    }
                    pageInfo.totalFrequency += tagInfo.totalFrequency;
                }
            }
        }

        for (Map.Entry<String, WordInfo> entry : pageWordInfo.entrySet()) { // for each word collected
            String wordText = entry.getKey();
            WordInfo info = entry.getValue();

            Word word = wordRepository.findByWord(wordText)
                    .orElseGet(() -> {
                        Word newWord = new Word();
                        newWord.setWord(wordText);
                        newWord.setTotalFrequency(0L);
                        System.out.println("created new word");
                        Word now = wordRepository.save(newWord);
                        wordRepository.flush();
                        return now;
                    });
            
            try {
                // Update word total frequency
            jdbcTemplate.update(
                    "UPDATE words SET total_frequency = total_frequency + ? WHERE id = ?",
                    info.totalFrequency, word.getId());

                // Insert into inverted_index with ON CONFLICT DO UPDATE
            jdbcTemplate.update(
                        "INSERT INTO inverted_index (word_id, doc_id, frequency) VALUES (?, ?, ?) " +
                        "ON CONFLICT (word_id, doc_id) DO UPDATE SET frequency = inverted_index.frequency + EXCLUDED.frequency",
                    word.getId(), documentEntity.getId(), info.totalFrequency);

                // Process each tag
            for (Map.Entry<String, Integer> tagEntry : info.tagFrequencies.entrySet()) {
                String tag = tagEntry.getKey();
                int tagFrequency = tagEntry.getValue();

                    // Insert into word_document_tags with ON CONFLICT DO UPDATE
                jdbcTemplate.update(
                            "INSERT INTO word_document_tags (word_id, doc_id, tag, frequency) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT (word_id, doc_id, tag) DO UPDATE SET frequency = word_document_tags.frequency + EXCLUDED.frequency",
                        word.getId(), documentEntity.getId(), tag, tagFrequency);
                }
            } catch (Exception e) {
                // Log error but continue processing other words
                System.err.println("Error indexing word " + wordText + " in document " + url + ": " + e.getMessage());
            }
        }
    }

    @Transactional
    public void buildIndex(Map<String, String> pages) {
        if (pages.isEmpty()) {
            return;
        }
        
        // For small batches, don't print as much information
        boolean isSmallBatch = pages.size() <= 20;
        
        if (!isSmallBatch) {
            System.out.println("------------------------------------------------------------");
            System.out.println("Starting indexing of " + pages.size() + " documents");
            System.out.println("------------------------------------------------------------");
        }
        
        int processedCount = 0;
        int totalDocuments = pages.size();
        long startTime = System.currentTimeMillis();
        
        for (Map.Entry<String, String> entry : pages.entrySet()) {
            String url = entry.getKey();
            String html = entry.getValue();
            
            try {
                // Process document
                indexPage(url, html);
                
                // Update progress
                processedCount++;
                
                // Only show detailed progress for larger batches
                if (!isSmallBatch && (processedCount % 10 == 0 || processedCount == totalDocuments)) {
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    double progressPercent = 100.0 * processedCount / totalDocuments;
                    System.out.printf("Indexed %d/%d documents (%.1f%%) - Elapsed time: %.2fs\n", 
                        processedCount, totalDocuments, progressPercent, elapsedTime / 1000.0);
                }
            } catch (Exception e) {
                System.out.println("Error indexing page " + url + ": " + e.getMessage());
            }
        }
        
        // Only show summary for larger batches
        if (!isSmallBatch) {
            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("------------------------------------------------------------");
            System.out.println("Indexing completed: " + processedCount + " documents indexed in " + (totalTime / 1000.0) + " seconds");
            System.out.println("Average time per document: " + (totalTime / (double)processedCount) + " ms");
            System.out.println("------------------------------------------------------------");
        }
        
        // Encourage garbage collection
        pages = null;
        System.gc();
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
        int pageSize = 200;
        int page = 0;
        boolean hasMore = true;
        while (hasMore) {
            List<Document> docs = documentRepository.findAll(PageRequest.of(page, pageSize)).getContent();
            if (docs.isEmpty()) {
                hasMore = false;
                continue;
            }
            for (Document doc : docs) {
                Long size = 0L;
                String cleanedHTML = preIndexer.cleanHTML(doc.getContent());
                List<String> words = preIndexer.tokenize(cleanedHTML);
                words = preIndexer.removeStopWords(words);
                words = preIndexer.Stemming(words);
                size = (long) words.size();
                docCnt.put(doc.getId(), size);
            }
            page++;
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

}

