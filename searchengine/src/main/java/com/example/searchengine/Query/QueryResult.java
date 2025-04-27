package com.example.searchengine.Query;

import java.util.List;
import java.util.ArrayList;

public class QueryResult {
    private List<String> words = new ArrayList<>();
    private List<String> phrases = new ArrayList<>();
    private List<String> stemmedWords = new ArrayList<>();
    
    public List<String> getWords() {
        return words;
    }
    
    public void setWords(List<String> words) {
        this.words = words;
    }
    
    public List<String> getPhrases() {
        return phrases;
    }
    
    public void setPhrases(List<String> phrases) {
        this.phrases = phrases;
    }
    
    public List<String> getStemmedWords() {
        return stemmedWords;
    }
    
    public void setStemmedWords(List<String> stemmedWords) {
        this.stemmedWords = stemmedWords;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Query Result:\n");
        
        if (words != null && !words.isEmpty()) {
            sb.append("Words: ").append(String.join(", ", words)).append("\n");
        }
        
        if (stemmedWords != null && !stemmedWords.isEmpty()) {
            sb.append("Stemmed Words: ").append(String.join(", ", stemmedWords)).append("\n");
        }
        
        if (phrases != null && !phrases.isEmpty()) {
            sb.append("Phrases: ").append(String.join(", ", phrases)).append("\n");
        }
        
        return sb.toString();
    }
} 