package com.example.searchengine.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryResult {
    private String originalQuery;
    private boolean isPhraseQuery;
    private List<String> stemmedWords;
    private List<String> phrases;
    private Map<String, List<Long>> matchingDocuments;
    private List<Map<String, Object>> results;
    private String errorMessage;
    private String operator;
    private List<String> suggestedQueries;

    public QueryResult() {
        this.stemmedWords = new ArrayList<>();
        this.phrases = new ArrayList<>();
        this.matchingDocuments = new HashMap<>();
        this.results = new ArrayList<>();
        this.suggestedQueries = new ArrayList<>();
    }

    public String getOriginalQuery() {
        return originalQuery;
    }
    
    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }
    
    public boolean isPhraseQuery() {
        return isPhraseQuery;
    }
    
    public void setPhraseQuery(boolean phraseQuery) {
        isPhraseQuery = phraseQuery;
    }
    
    public List<String> getStemmedWords() {
        return stemmedWords;
    }
    
    public void setStemmedWords(List<String> stemmedWords) {
        this.stemmedWords = stemmedWords;
    }
    
    public List<String> getPhrases() {
        return phrases;
    }

    public void setPhrases(List<String> phrases) {
        this.phrases = phrases;
    }

    public Map<String, List<Long>> getMatchingDocuments() {
        return matchingDocuments;
    }

    public void setMatchingDocuments(Map<String, List<Long>> matchingDocuments) {
        this.matchingDocuments = matchingDocuments;
    }

    public List<Map<String, Object>> getResults() {
        return results;
    }

    public void setResults(List<Map<String, Object>> results) {
        this.results = results;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public int getTotalResults() {
        return results != null ? results.size() : 0;
    }

    public String getResultsAsString() {
        StringBuilder builder = new StringBuilder();
        
        builder.append("Query: ").append(originalQuery).append("\n");
        
        if (operator != null && !operator.isEmpty()) {
            builder.append("Operator: ").append(operator).append("\n");
        }
        
        builder.append("Processed as ");
        if (isPhraseQuery) {
            builder.append("phrase query");
        } else {
            builder.append("regular query");
        }
        builder.append("\n");
        
        if (!phrases.isEmpty()) {
            builder.append("Phrase: [").append(String.join(", ", phrases)).append("]\n");
        }
        
        if (!stemmedWords.isEmpty()) {
            builder.append("Stemmed: [").append(String.join(", ", stemmedWords)).append("]\n");
        }
        
        builder.append("Total results: ").append(getTotalResults()).append("\n\n");
        
        for (Map<String, Object> result : results) {
            builder.append("Title: ").append(result.get("title")).append("\n");
            builder.append("URL: ").append(result.get("url")).append("\n");
            builder.append("Score: ").append(result.get("score")).append("\n");
            builder.append("Snippet: ").append(result.get("snippet")).append("\n\n");
        }
        
        return builder.toString();
    }

    public List<String> getSuggestedQueries() {
        return suggestedQueries;
    }
    
    public void setSuggestedQueries(List<String> suggestedQueries) {
        this.suggestedQueries = suggestedQueries;
    }
} 