package com.example.searchengine.utils;

/**
 * Utility class for displaying progress bars in the console
 */
public class ProgressBarUtil {
    
    /**
     * Start a progress operation with a message
     */
    public static void startProgress(String message) {
        System.out.println(message);
    }
    
    /**
     * Display a progress bar in the console
     * 
     * @param current Current progress
     * @param total Total items to process
     * @param barLength Length of the progress bar in chars
     */
    public static void displayProgressBar(int current, int total, int barLength) {
        if (total <= 0) return;
        
        float percent = (float) current / total;
        int completedLength = Math.round(barLength * percent);
        
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barLength; i++) {
            bar.append(i < completedLength ? "=" : " ");
        }
        bar.append("] ");
        bar.append(String.format("%.1f%%", percent * 100));
        bar.append(String.format(" (%d/%d)", current, total));
        
        // Clear line and print progress
        System.out.print("\r" + bar);
        
        // Add newline when complete
        if (current >= total) {
            System.out.println();
        }
    }
    
    /**
     * Complete a progress operation with a message
     */
    public static void completeProgress(String message) {
        System.out.println("\n" + message);
    }
    
    /**
     * Calculate how frequently to update the progress bar
     * 
     * @param total Total number of items
     * @param percentInterval How often to update (in percent intervals)
     * @return Number of items between updates
     */
    public static int getUpdateFrequency(int total, int percentInterval) {
        if (total <= 0 || percentInterval <= 0 || percentInterval > 100) {
            return 1;
        }
        
        // Calculate items per percentage point
        int itemsPerPercent = Math.max(1, total / 100);
        
        // Return items per update interval
        return Math.max(1, itemsPerPercent * percentInterval);
    }
} 