package com.example.searchengine.Indexer.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A prefix tree (trie) implementation for efficient word lookups and suggestions
 */
public class Trie {
    private TrieNode root;
    
    public Trie() {
        root = new TrieNode();
    }
    
    /**
     * Insert a word into the trie
     * 
     * @param word The word to insert
     * @param wordId The ID of the word in the database
     */
    public void insert(String word, Long wordId) {
        TrieNode current = root;
        
        for (char c : word.toCharArray()) {
            current.children.putIfAbsent(c, new TrieNode());
            current = current.children.get(c);
        }
        
        current.isEndOfWord = true;
        current.wordId = wordId;
        current.word = word;
    }
    
    /**
     * Check if the trie contains a word
     * 
     * @param word The word to check
     * @return true if the word exists, false otherwise
     */
    public boolean contains(String word) {
        TrieNode node = getNode(word);
        return node != null && node.isEndOfWord;
    }
    
    /**
     * Get the database ID for a word
     * 
     * @param word The word to look up
     * @return The word ID or null if not found
     */
    public Long getWordId(String word) {
        TrieNode node = getNode(word);
        return (node != null && node.isEndOfWord) ? node.wordId : null;
    }
    
    /**
     * Find suggestions for a prefix
     * 
     * @param prefix The prefix to suggest completions for
     * @param limit Maximum number of suggestions to return
     * @return List of word suggestions
     */
    public List<String> getSuggestions(String prefix, int limit) {
        List<String> suggestions = new ArrayList<>();
        TrieNode prefixNode = getNode(prefix);
        
        if (prefixNode != null) {
            collectWords(prefixNode, suggestions, limit);
        }
        
        return suggestions;
    }
    
    /**
     * Get suggestions with their IDs
     * 
     * @param prefix The prefix to suggest completions for
     * @param limit Maximum number of suggestions to return
     * @return Map of words to their database IDs
     */
    public Map<String, Long> getSuggestionsWithIds(String prefix, int limit) {
        Map<String, Long> suggestions = new HashMap<>();
        TrieNode prefixNode = getNode(prefix);
        
        if (prefixNode != null) {
            collectWordsWithIds(prefixNode, suggestions, limit);
        }
        
        return suggestions;
    }
    
    private TrieNode getNode(String word) {
        TrieNode current = root;
        
        for (char c : word.toCharArray()) {
            if (!current.children.containsKey(c)) {
                return null;
            }
            current = current.children.get(c);
        }
        
        return current;
    }
    
    private void collectWords(TrieNode node, List<String> result, int limit) {
        if (result.size() >= limit) {
            return;
        }
        
        if (node.isEndOfWord) {
            result.add(node.word);
        }
        
        for (TrieNode child : node.children.values()) {
            collectWords(child, result, limit);
            if (result.size() >= limit) {
                return;
            }
        }
    }
    
    private void collectWordsWithIds(TrieNode node, Map<String, Long> result, int limit) {
        if (result.size() >= limit) {
            return;
        }
        
        if (node.isEndOfWord) {
            result.put(node.word, node.wordId);
        }
        
        for (TrieNode child : node.children.values()) {
            collectWordsWithIds(child, result, limit);
            if (result.size() >= limit) {
                return;
            }
        }
    }
    
    /**
     * Node class for the trie
     */
    private static class TrieNode {
        Map<Character, TrieNode> children;
        boolean isEndOfWord;
        Long wordId;
        String word;
        
        TrieNode() {
            children = new HashMap<>();
            isEndOfWord = false;
            wordId = null;
            word = null;
        }
    }
} 