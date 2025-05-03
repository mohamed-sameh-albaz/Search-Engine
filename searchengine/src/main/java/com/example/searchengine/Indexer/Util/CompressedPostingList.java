package com.example.searchengine.Indexer.Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility for compressing and decompressing posting lists
 * This reduces memory usage for large inverted index entries
 */
public class CompressedPostingList {

    /**
     * Compress a list of document IDs into a byte array
     * 
     * @param docIds List of document IDs
     * @return Compressed byte array
     */
    public static byte[] compress(List<Long> docIds) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            // Store count
            int count = docIds.size();
            gzipOut.write((count >> 24) & 0xFF);
            gzipOut.write((count >> 16) & 0xFF);
            gzipOut.write((count >> 8) & 0xFF);
            gzipOut.write(count & 0xFF);

            // Delta encoding - store differences between consecutive IDs
            long prev = 0;
            for (Long docId : docIds) {
                long delta = docId - prev;
                prev = docId;
                
                // Variable-length encoding
                while (delta >= 128) {
                    gzipOut.write((int)((delta & 0x7F) | 0x80));
                    delta >>= 7;
                }
                gzipOut.write((int)(delta & 0x7F));
            }
        }
        return baos.toByteArray();
    }

    /**
     * Decompress a byte array into a list of document IDs
     * 
     * @param compressed Compressed byte array
     * @return List of document IDs
     */
    public static List<Long> decompress(byte[] compressed) throws IOException {
        List<Long> docIds = new ArrayList<>();
        
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        try (GZIPInputStream gzipIn = new GZIPInputStream(bais)) {
            // Read count
            int count = 0;
            for (int i = 0; i < 4; i++) {
                count = (count << 8) | gzipIn.read();
            }
            
            // Read deltas and reconstruct IDs
            long prev = 0;
            for (int i = 0; i < count; i++) {
                long delta = 0;
                int shift = 0;
                int b;
                
                // Read variable-length encoded delta
                do {
                    b = gzipIn.read();
                    delta |= (long)(b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);
                
                prev += delta;
                docIds.add(prev);
            }
        }
        
        return docIds;
    }

    /**
     * Get a subset of document IDs from a compressed posting list
     * without decompressing the entire list
     * 
     * @param compressed Compressed byte array
     * @param offset Starting position (0-based)
     * @param limit Maximum number of IDs to return
     * @return Subset of document IDs
     */
    public static List<Long> getSubset(byte[] compressed, int offset, int limit) throws IOException {
        List<Long> result = new ArrayList<>();
        
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        try (GZIPInputStream gzipIn = new GZIPInputStream(bais)) {
            // Read count
            int count = 0;
            for (int i = 0; i < 4; i++) {
                count = (count << 8) | gzipIn.read();
            }
            
            // Skip to offset
            long prev = 0;
            for (int i = 0; i < offset && i < count; i++) {
                long delta = 0;
                int shift = 0;
                int b;
                
                do {
                    b = gzipIn.read();
                    delta |= (long)(b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);
                
                prev += delta;
            }
            
            // Read the required subset
            for (int i = 0; i < limit && (i + offset) < count; i++) {
                long delta = 0;
                int shift = 0;
                int b;
                
                do {
                    b = gzipIn.read();
                    delta |= (long)(b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);
                
                prev += delta;
                result.add(prev);
            }
        }
        
        return result;
    }
} 