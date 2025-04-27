package com.example.searchengine.Query;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

@Service
public class QueryService {
    
    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);
    private final HashSet<String> stopWords;
    private final Analyzer analyzer;

    public QueryService() {
        this.stopWords = getStopWords();
        this.analyzer = new StandardAnalyzer();
    }

    public Map<String, List<String>> processQuery(String query) {
        logger.info("Processing query: {}", query);
        
        List<String> phrases = new ArrayList<>();
        List<String> stemmedWords = new ArrayList<>();
        
        try {
            // Extract phrases first (text between quotes)
            String[] parts = query.split("\"");
            for (int i = 1; i < parts.length; i += 2) {
                if (i < parts.length) {
                    String phrase = parts[i].trim();
                    if (!phrase.isEmpty()) {
                        phrases.add(phrase);
                    }
                }
            }
            
            // Process non-phrase parts
            String nonPhraseParts = query.replaceAll("\"[^\"]*\"", " ").trim();
            String[] words = nonPhraseParts.toLowerCase().split("\\s+");
            
            for (String word : words) {
                word = word.trim();
                if (!word.isEmpty() && !stopWords.contains(word) && !word.equals("and")) {
                    String stemmed = stemWord(word);
                    if (!stemmed.isEmpty()) {
                        stemmedWords.add(stemmed);
                    }
                }
            }

            // Log the results
            logger.info("Extracted phrases: {}", phrases);
            logger.info("Stemmed words: {}", stemmedWords);
            
        } catch (Exception e) {
            logger.error("Error processing query: {}", query, e);
            phrases = new ArrayList<>();
            stemmedWords = new ArrayList<>();
        }
        
        Map<String, List<String>> result = new HashMap<>();
        result.put("phrases", phrases);
        result.put("stemmed", stemmedWords);
        return result;
    }

    private String stemWord(String word) {
        try (TokenStream tokenStream = analyzer.tokenStream("field", new StringReader(word));
             TokenStream snowballStream = new SnowballFilter(tokenStream, "English")) {
            CharTermAttribute termAttr = snowballStream.addAttribute(CharTermAttribute.class);
            snowballStream.reset();
            if (snowballStream.incrementToken()) {
                return termAttr.toString();
            }
            snowballStream.end();
        } catch (IOException e) {
            logger.error("Error stemming word: {}", word, e);
        }
        return word;
    }

    private static HashSet<String> getStopWords() {
        HashSet<String> stopWordsSet = new HashSet<>();
        try {
            File stopFile = new File("StopWords.txt").getAbsoluteFile();
            if (!stopFile.exists()) {
                logger.warn("StopWords.txt not found, using default stop words");
                stopWordsSet.addAll(Arrays.asList("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "he", "in", "is", "it", "its", "of", "on", "that", "the", "this", "to", "was", "were", "will", "with"));
                return stopWordsSet;
            }
            Scanner reader = new Scanner(stopFile);
            while (reader.hasNextLine()) {
                String word = reader.nextLine();
                stopWordsSet.add(word);
            }
            reader.close();
        } catch (Exception e) {
            logger.error("An exception occurred while reading the stop words!", e);
        }
        return stopWordsSet;
    }
} 