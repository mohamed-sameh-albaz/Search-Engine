package com.example.searchengine.utils;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Utility class for displaying document relationships
 */
public class DocumentRelationshipUtil {
    
    /**
     * Display statistics about document relationships
     * 
     * @param relationMap Map of document relationships
     * @param topParents Number of top parents to display
     * @param topChildren Number of top children per parent to display
     */
    public static void displayRelationships(Map<Long, Map<Long, Integer>> relationMap, int topParents, int topChildren) {
        if (relationMap == null || relationMap.isEmpty()) {
            System.out.println("No document relationships to display.");
            return;
        }
        
        System.out.println("\nDocument Relationship Statistics:");
        System.out.println("================================");
        
        // Count documents
        int totalParents = relationMap.size();
        int totalRelationships = relationMap.values().stream()
                .mapToInt(map -> map.size())
                .sum();
        
        System.out.println("Total parent documents: " + totalParents);
        System.out.println("Total relationships: " + totalRelationships);
        
        if (totalParents == 0) return;
        
        // Calculate avg children per parent
        double avgChildrenPerParent = (double) totalRelationships / totalParents;
        System.out.printf("Average children per parent: %.2f\n", avgChildrenPerParent);
        
        // Find parent with most children
        Entry<Long, Map<Long, Integer>> maxParent = relationMap.entrySet().stream()
                .max((e1, e2) -> Integer.compare(e1.getValue().size(), e2.getValue().size()))
                .orElse(null);
        
        if (maxParent != null) {
            System.out.println("Max children for a parent: " + maxParent.getValue().size() + 
                    " (Document ID: " + maxParent.getKey() + ")");
        }
        
        // Display top parents by child count
        System.out.println("\nTop " + topParents + " parents by number of children:");
        List<Entry<Long, Map<Long, Integer>>> topParentsList = relationMap.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .limit(topParents)
                .collect(Collectors.toList());
        
        for (Entry<Long, Map<Long, Integer>> entry : topParentsList) {
            System.out.println("Document ID: " + entry.getKey() + " - Children: " + entry.getValue().size());
        }
        
        System.out.println("\nAnalysis complete.");
    }
} 