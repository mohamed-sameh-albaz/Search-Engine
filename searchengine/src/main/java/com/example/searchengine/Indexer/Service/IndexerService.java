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

    @Transactional
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
            jdbcTemplate.update(
                    "UPDATE words SET total_frequency = total_frequency + ? WHERE id = ?",
                    info.totalFrequency, word.getId());

            jdbcTemplate.update(
                    "INSERT INTO inverted_index (word_id, doc_id, frequency) VALUES (?, ?, ?) ",
                    word.getId(), documentEntity.getId(), info.totalFrequency);

            for (Map.Entry<String, Integer> tagEntry : info.tagFrequencies.entrySet()) {
                String tag = tagEntry.getKey();
                int tagFrequency = tagEntry.getValue();
                jdbcTemplate.update(
                        "INSERT INTO word_document_tags (word_id, doc_id, tag, frequency) VALUES (?, ?, ?, ?) ",
                        word.getId(), documentEntity.getId(), tag, tagFrequency);
            }
        }
    }

    @Transactional
    public void buildIndex(Map<String, String> pages) {
        System.out.println("Start indexing");
        int numThreads = Runtime.getRuntime().availableProcessors();
        // ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        System.out.println("with pools: " + numThreads);
        for (Map.Entry<String, String> entry : pages.entrySet()) {
            String url = entry.getKey();
            String html = entry.getValue();
            // executor.submit(() -> {
            try {
                System.out.println("GOoooooooooooooooooooooooooooooooooooo");
                // createInvertedIndex(url, html);
                indexPage(url, html);
            } catch (Exception e) {
                System.out.println("Error indexing page " + url + ": " + e.getMessage());
            }
            // });
        }

        // executor.shutdown();
        // try {
        // if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        // executor.shutdownNow();
        // }
        // } catch (InterruptedException e) {
        // executor.shutdownNow();
        // Thread.currentThread().interrupt();
        // }
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
        Map<String, Map<Long, Integer>> index = new HashMap<>();
        int pageSize = 10;
        int page = 0;
        boolean hasMore = true;

        while (hasMore) {
            List<Word> words = wordRepository.findAll(PageRequest.of(page, pageSize)).getContent();
            System.out.println("ssssssssss" + words.size());
            if (words.isEmpty()) {
                hasMore = false;
                continue;
            }

            for (Word word : words) {
                List<InvertedIndex> mappings = invertedIndexRepository.findByWord(word,
                        PageRequest.of(0, Integer.MAX_VALUE));

                for (InvertedIndex mapping : mappings) {
                    Map<Long, Integer> docFreq = new HashMap<>();

                    docFreq.put(mapping.getDocument().getId(), mapping.getFrequency());
                    index.put(mapping.getWord().getWord(), docFreq);
                }

            }
            page++;
        }
        return index;
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

}
