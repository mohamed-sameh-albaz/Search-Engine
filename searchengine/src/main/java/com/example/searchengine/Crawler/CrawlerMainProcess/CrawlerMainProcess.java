package com.example.searchengine.Crawler.CrawlerMainProcess;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.searchengine.Crawler.Repository.DocumentsRepository;
import com.example.searchengine.Crawler.Repository.RelatedLinksRepository;

import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
@RequestMapping("/crawler")
public class CrawlerMainProcess {
    private DocumentsRepository documentsRepository;
    private RelatedLinksRepository relatedLinksRepository;
    ServeDataBase serveDataBase = new ServeDataBase(documentsRepository, relatedLinksRepository);
    static AtomicInteger count = new AtomicInteger(0);
    private static final int MAX_DOCUMENTS = 6000; // Configurable limit
    private static boolean stopFlag = false;
    private static boolean isRunning = false;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
    private Set<String> robotChecked = Collections.synchronizedSet(new HashSet<>());
    private Set<String> excludedLinks = Collections.synchronizedSet(new HashSet<>());
    Thread[] threads;
    private static long startTime = 0;
    private Map<Long, Map<Long, Integer>> cachedRelationMap = null;
    private int[][] cachedRelationMatrix = null;

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        int totalDocuments = serveDataBase.getAllVisited().size();
        
        status.put("isRunning", isRunning);
        status.put("documentCount", totalDocuments);
        status.put("maxDocuments", MAX_DOCUMENTS);
        
        if (isRunning) {
            status.put("activeThreads", getActiveThreadCount());
            status.put("elapsedTimeSeconds", (System.currentTimeMillis() - startTime) / 1000);
            status.put("crawledInThisSession", count.get());
        }
        
        return status;
    }
    
    private int getActiveThreadCount() {
        if (threads == null) return 0;
        
        int active = 0;
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive()) {
                active++;
            }
        }
        return active;
    }

    @GetMapping
    public Map<String, Object> startCrawlingGet(@RequestParam(required = true, defaultValue = "4") int thread_num) {
        Map<String, Object> response = new HashMap<>();
        
        if (isRunning) {
            response.put("status", "Crawler is already running");
            response.put("activeThreads", getActiveThreadCount());
            return response;
        }
        
        count.set(0);
        stopFlag = false;
        isRunning = true;
        startTime = System.currentTimeMillis();
        
        int initialCount = serveDataBase.getAllVisited().size();
        response.put("initialDocumentCount", initialCount);
        
        threads = new Thread[thread_num];
        for (int i = 0; i < thread_num; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    crawl();
                } catch (Exception e) {
                    System.err.println("Error in crawler thread " + threadId + ": " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    if (getActiveThreadCount() == 0) {
                        isRunning = false;
                    }
                }
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }
        
        response.put("status", "Crawler started with " + thread_num + " threads");
        response.put("message", "Crawling in progress. Check your database for new documents.");
        
        return response;
    }

    @PostMapping
    public Map<String, Object> startCrawling(@RequestParam(required = true, defaultValue = "4") int thread_num) {
        Map<String, Object> response = new HashMap<>();
        
        if (isRunning) {
            response.put("status", "Crawler is already running");
            response.put("activeThreads", getActiveThreadCount());
            return response;
        }
        
        // Validate seed links before starting crawler
        String[] seedLinks = ReadseedLinks();
        if (seedLinks == null || seedLinks.length == 0) {
            response.put("status", "error");
            response.put("message", "No seed links available. Check seedlinks.txt file.");
            return response;
        }
        
        // Test first seed link to ensure connectivity
        try {
            String testUrl = seedLinks[0];
            Connection.Response testResponse = Jsoup.connect(testUrl)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "*")
                .timeout(5000)
                .method(Connection.Method.HEAD)
                .execute();
                
            response.put("testConnection", "Successful - " + testUrl + " returned status " + testResponse.statusCode());
        } catch (Exception e) {
            response.put("status", "warning");
            response.put("testConnection", "Failed - " + e.getMessage());
            // Continue anyway, as other seeds might work
        }
        
        // Clear state for fresh crawl (optional, remove if you want to resume)
        stopFlag = false;
        isRunning = true;
        startTime = System.currentTimeMillis();
        count.set(0);
        
        int initialCount = serveDataBase.getAllVisited().size();
        response.put("initialDocumentCount", initialCount);
        response.put("seedLinksCount", seedLinks.length);
        
        // Configure and start crawler threads
        threads = new Thread[thread_num];
        for (int i = 0; i < thread_num; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
            try {
                    crawl();
                } catch (Exception e) {
                    System.err.println("Error in crawler thread " + threadId + ": " + e.getMessage());
                e.printStackTrace();
                } finally {
                    if (getActiveThreadCount() == 0) {
                        isRunning = false;
                    }
            }
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }

        response.put("status", "Crawler started with " + thread_num + " threads");
        response.put("message", "Crawling in progress using " + seedLinks.length + " seed URLs.");
        
        return response;
    }

    public String[] ReadseedLinks() {
        try {
            File file = new File("seedlinks.txt");
            BufferedReader Reader = new BufferedReader(new FileReader(file));
            String line = null;
            List<String> seedLinksList = new ArrayList<>();
            
            while ((line = Reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    seedLinksList.add(line.trim());
            }
            }
            
            Reader.close();
            
            String[] seedLinks = new String[seedLinksList.size()];
            seedLinksList.toArray(seedLinks);
            
            System.out.println("Successfully read " + seedLinks.length + " seed links");
            return seedLinks;
        } catch (Exception e) {
            System.out.println("Error reading seed links: " + e.getMessage());
            e.printStackTrace();
            return new String[] {
                "https://en.wikipedia.org",
                "https://www.bbc.com"
            };
        }
    }

    public void checkRobotsTxt(String baseUrl) {
        try {
            URL realUrl = new URL(baseUrl);
            String robotUrl = realUrl.getProtocol() + "://" + realUrl.getHost() + "/robots.txt";
            
            synchronized (this.robotChecked) {
                if (robotChecked.contains(robotUrl))
                    return;
                System.out.println("Reading Excluded Links from: " + robotUrl);
                robotChecked.add(robotUrl);
            }

            Connection.Response response = Jsoup.connect(robotUrl)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "*")
                    .execute();
                    
            if (response.statusCode() > 399) {
                return;
            }
            
            String robotsTxt = response.parse().text();
            String[] lines = robotsTxt.split(" ");
            boolean toExclude = false;
            
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].compareTo("User-agent:") == 0 && lines[i + 1].compareTo("*") == 0) {
                    toExclude = true;
                } else if (lines[i].compareTo("User-agent:") == 0 && lines[i + 1].compareTo("*") != 0) {
                    toExclude = false;
                }
                if (toExclude && lines[i - 1].compareTo("Disallow:") == 0) {
                    String disallowedPath;
                    if (lines[i].startsWith("/")) {
                        disallowedPath = realUrl.getProtocol() + "://" + realUrl.getHost() + lines[i];
                    } else {
                        disallowedPath = realUrl.getProtocol() + "://" + realUrl.getHost() + '/' + lines[i];
                    }
                    synchronized (this.excludedLinks) {
                        excludedLinks.add(disallowedPath);
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore errors with robots.txt
        }
    }

    public boolean isAllowedByRobots(String url) {
        try {
            URL urlObj = new URL(url);
            String baseUrl = urlObj.getProtocol() + "://" + urlObj.getHost();
            
            if (!robotChecked.contains(baseUrl + "/robots.txt")) {
                checkRobotsTxt(baseUrl);
            }
            
            for (String disallowed : excludedLinks) {
                if (url.startsWith(disallowed)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return true; // Allow if there's an error
        }
    }

    public void crawl() {
        String[] urlFromSeed = ReadseedLinks();
        int seedLinksCount = urlFromSeed.length;
        BlockingQueue<com.example.searchengine.Crawler.Entities.Document> queue = new LinkedBlockingQueue<>();
        ConcurrentHashMap<String, com.example.searchengine.Crawler.Entities.Document> visitedUrls = new ConcurrentHashMap<>();
        List<com.example.searchengine.Crawler.Entities.Document> crawlers = new ArrayList<>();

        // First load existing documents from the database
        try {
            crawlers = serveDataBase.getAllVisited();
            System.out.println("Loaded " + crawlers.size() + " existing documents from database");
            
            // Add existing documents to our tracking maps
            for (com.example.searchengine.Crawler.Entities.Document doc : crawlers) {
                if (doc.getUrl() != null) {
                visitedUrls.put(doc.getUrl(), doc);
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading existing documents: " + e.getMessage());
        }

        // Process each seed URL separately to ensure they get crawled
        for (int i = 0; i < seedLinksCount && !Thread.currentThread().isInterrupted() && !stopFlag; i++) {
            String seedUrl = urlFromSeed[i];
            System.out.println("Thread " + Thread.currentThread().getId() + " processing seed URL: " + seedUrl);
            
            // Skip seed URL if we've already crawled it
            if (visitedUrls.containsKey(seedUrl)) {
                System.out.println("Seed URL already visited: " + seedUrl);
                continue;
            }
            
            // Create a new document for this seed
            com.example.searchengine.Crawler.Entities.Document seedDoc = null;
            try {
                // Check robots.txt for the seed URL's domain
                checkRobotsTxt(seedUrl);
                
                if (!isAllowedByRobots(seedUrl)) {
                    System.out.println("Seed URL not allowed by robots.txt: " + seedUrl);
                    continue;
                }
                
                // Normalize the URL
                String normalizedUrl = normalizeURL(seedUrl);
                if (normalizedUrl == null) {
                    System.out.println("Failed to normalize seed URL: " + seedUrl);
                    continue;
                }
                
                // Create a document for this seed
                seedDoc = new com.example.searchengine.Crawler.Entities.Document();
                seedDoc.setUrl(normalizedUrl);
                
                // Try to fetch the content for the seed URL
                try {
                    Document jsoupDoc = Jsoup.connect(normalizedUrl)
                        .userAgent(USER_AGENT)
                        .header("Accept-Language", "*")
                        .timeout(10000) // Use a longer timeout for seed URLs
                        .get();
                    
                    String title = jsoupDoc.title();
                    String content = jsoupDoc.html();
                    seedDoc.setUrl(normalizedUrl);
                    seedDoc.setTitle(title);
                    seedDoc.setContent(content);
                    seedDoc.setStatus("visited");
                    
                    // Save the seed document to the database
                    serveDataBase.saveToDatabase(seedDoc);
                    int newCount = count.incrementAndGet();
                    System.out.println("Added seed URL to database: " + normalizedUrl + ", count: " + newCount);
                    
                    // Add to visited URLs to prevent re-crawling
                    visitedUrls.put(normalizedUrl, seedDoc);
                    
                    // Add all links from this seed URL to the queue
                    processLinksFromPage(jsoupDoc, normalizedUrl, seedDoc, queue, visitedUrls);
                    
                    // Process the queue until it's empty or we reach the maximum number of documents
                    processQueue(queue, visitedUrls);
                } catch (Exception e) {
                    System.err.println("Error fetching seed URL " + normalizedUrl + ": " + e.getMessage());
                }
            } catch (Exception e) {
                System.err.println("Error processing seed URL " + seedUrl + ": " + e.getMessage());
            }
        }
        System.out.println("Thread " + Thread.currentThread().getId() + " finished crawling");
    }

    private void processLinksFromPage(Document doc, String parentUrl, com.example.searchengine.Crawler.Entities.Document parentDoc, 
                                    BlockingQueue<com.example.searchengine.Crawler.Entities.Document> queue,
                                    ConcurrentHashMap<String, com.example.searchengine.Crawler.Entities.Document> visitedUrls) {
        try {
            URI parentUri = new URI(parentUrl);
            Elements links = doc.getElementsByTag("a");
            
            for (Element link : links) {
                if (count.get() >= MAX_DOCUMENTS || Thread.currentThread().isInterrupted() || stopFlag) {
                    return;
                }
                
                String linkUrl = link.attr("href");
                if (linkUrl.isEmpty() || linkUrl.charAt(0) == '#') {
                    continue;
                }
                
                try {
                    URI resolvedUri = new URI(linkUrl);
                    resolvedUri = parentUri.resolve(resolvedUri);
                    String finalUrl = normalizeURL(resolvedUri.toString());
                    
                    if (finalUrl == null) {
                        continue;
                    }
                    
                    // Save the relationship between parent and child
                        try {
                            serveDataBase.saveRelatedLinks(parentUrl.toString(), finalUrl);
                        } catch (Exception e) {
                        // Just log and continue
                            System.out.println("Error saving related links: " + e.getMessage());
                        }

                    // Add to queue if not visited
                    if (!visitedUrls.containsKey(finalUrl) && isAllowedByRobots(finalUrl)) {
                        com.example.searchengine.Crawler.Entities.Document childDoc = new com.example.searchengine.Crawler.Entities.Document();
                        childDoc.setUrl(finalUrl);
                        childDoc.setStatus("to_visit");
                        if (parentDoc.getId() != null) {
                            childDoc.setParentDocId(parentDoc.getId());
                        }
                        
                        // Mark as visited before adding to queue to prevent duplicates
                        visitedUrls.put(finalUrl, childDoc);
                        queue.add(childDoc);
                        
                        System.out.println("Added to queue: " + finalUrl);
                    }
                } catch (Exception e) {
                    // Just log and continue
                    System.out.println("Error processing link " + linkUrl + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing links from " + parentUrl + ": " + e.getMessage());
        }
                }

    private void processQueue(BlockingQueue<com.example.searchengine.Crawler.Entities.Document> queue,
                             ConcurrentHashMap<String, com.example.searchengine.Crawler.Entities.Document> visitedUrls) {
        while (!queue.isEmpty() && !Thread.currentThread().isInterrupted() && !stopFlag) {
                if (count.get() >= MAX_DOCUMENTS) {
                close();
                return;
            }
            
            com.example.searchengine.Crawler.Entities.Document docElement = queue.poll();
            if (docElement == null) continue;
            
            String currentUrl = docElement.getUrl();
            if (currentUrl == null) continue;
            
            // Skip if the URL was already processed while in the queue
            if (docElement.getStatus() != null && docElement.getStatus().equals("visited")) {
                continue;
            }
            
            try {
                Document jsoupDoc = Jsoup.connect(currentUrl)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "*")
                    .timeout(5000)
                    .get();
                
                String title = jsoupDoc.title();
                String content = jsoupDoc.html();
                docElement.setUrl(currentUrl);
                docElement.setTitle(title);
                docElement.setContent(content);
                docElement.setStatus("visited");
                
                // Save to database
                serveDataBase.saveToDatabase(docElement);
                int newCount = count.incrementAndGet();
                System.out.println("Added to database: " + currentUrl + ", count: " + newCount);
                
                if (newCount >= MAX_DOCUMENTS) {
                    close();
                    return;
                }
                
                // Process links from this page
                processLinksFromPage(jsoupDoc, currentUrl, docElement, queue, visitedUrls);
            } catch (Exception e) {
                System.out.println("Error crawling " + currentUrl + ": " + e.getMessage());
            }
        }
    }

    public void close() {
            stopFlag = true;
        isRunning = false;
        System.out.println("Crawler is stopping...");
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }
    }

    public String normalizeURL(String url) {
        try {
            URL url_obj = URI.create(url).toURL();
            // Check if the URL is valid and not already processed
            String urlString = url_obj.toString();
            if (url_obj == null || url_obj.getHost() == null) {
                return null;
            }

            String protocol = url_obj.getProtocol().toLowerCase();
            String token = url_obj.getHost().toLowerCase();
            String portPart = (url_obj.getPort() == -1 || protocol.equals("https") || protocol.equals("http")) ? "" : ":" + url_obj.getPort();
            String path = url_obj.getPath() == null ? "" : url_obj.getPath().toLowerCase();
            String query = url_obj.getQuery() == null ? "" : url_obj.getQuery();
            String fragment = url_obj.getRef() == null ? "" : "#" + url_obj.getRef();
            // edit path
            String editedpath = path;
            editedpath = editedpath.replaceAll("%2e", ".");
            editedpath = editedpath.replaceAll("%2f", "/");

            editedpath = editedpath.replaceAll("/[^/]+/\\.\\./", "/");
            editedpath = editedpath.replaceAll("/\\.", "");
            editedpath = editedpath.replaceAll("/{2,}", "/");
            if (!path.equals("/") && path.endsWith("/")) {
                editedpath = editedpath.substring(0, editedpath.length() - 1);
                editedpath = editedpath.replaceAll("/+$", "/");
            }
            urlString = urlString.replace(path, editedpath);
            
            // Replace host with normalized token and handle port
            String hostPort = url_obj.getHost();
            if (url_obj.getPort() != -1) {
                hostPort += ":" + url_obj.getPort();
            }
            
            String normalizedHostPort = token + portPart;
            urlString = urlString.replace(hostPort, normalizedHostPort);
            
            // edit query
            StringBuilder editedquery = new StringBuilder();
            String[] params = query.split("&");
            Arrays.sort(params);

            for (String param : params) {
                String[] toencode = param.split("=", 2);
                if (toencode.length == 2) {
                    try {
                        String encoded = toencode[1];
                        encoded = URLEncoder.encode(encoded, "UTF-8");
                        System.out.println("encoded: " + encoded);
                        param = param.replace(toencode[1], encoded);
                    } catch (java.io.UnsupportedEncodingException e) {
                        System.out.println("Encoding error: " + e.getMessage());
                    }
                }
                editedquery.append(param).append("&");
            }
            if (editedquery.length() > 0) {
                editedquery.deleteCharAt(editedquery.length() - 1); // Remove the last '&'
            }
            urlString = urlString.replace(query, editedquery.toString());
            urlString = urlString.replace(fragment, "");
            if (!urlString.isEmpty() && (urlString.endsWith("/") || urlString.endsWith("\\")))
            {
                urlString = urlString.substring(0, urlString.length() - 1);
            }
            return urlString;
        } catch (Exception e) {
            return null;
        }
    }

    public Map<Long, Map<Long, Integer>> relationBetweenDocs() {
        // Return cached result if available
        if (cachedRelationMap != null) {
            System.out.println("Using cached relationship map");
            return cachedRelationMap;
        }
        
        // Use more efficient HashMap instead of Hashtable which has synchronized methods
        Map<Long, Map<Long, Integer>> relationMap = new HashMap<>();
        
        // Display start message
        displayProgress("Loading document relationships...");
        
        // Get relationships using repository method
        List<Object[]> relatedLinksIDs = relatedLinksRepository.getRelatedLinksIDs();
        
        // Count total items for progress tracking
        int total = relatedLinksIDs.size();
        System.out.println("Total relationships to process: " + total);
        
        // Performance optimization: pre-size the maps based on number of documents
        int expectedDocsPerParent = 20; // Estimated average number of children per parent
        int current = 0;
        int displayFrequency = getUpdateFrequency(total, 1); // Update every 1%
        
        // Process in batches for better performance
        int batchSize = 5000;
        for (int i = 0; i < total; i += batchSize) {
            int endIndex = Math.min(i + batchSize, total);
            List<Object[]> batch = relatedLinksIDs.subList(i, endIndex);
            
            for (Object[] row : batch) {
                Long childDocId = (Long) row[1];
                Long parentDocId = (Long) row[0];
                
                // Create child map with initial capacity if it doesn't exist
                relationMap.computeIfAbsent(parentDocId, k -> new HashMap<>(expectedDocsPerParent)).put(childDocId, 1);
                
                // Update progress bar
                current++;
                if (current % displayFrequency == 0 || current == total) {
                    displayProgress("Processing relationships complete!");
                }
            }
        }
        
        // Use the utility for displaying document relationships
        displayRelationships(relationMap, 10, 10);
        
        // Cache the result
        cachedRelationMap = relationMap;
        
        return relationMap;
    }

    /**
     * Creates a sparse matrix representation of document relationships
     * This is much more memory efficient than a full matrix for large document sets
     */
    public int[][] relationMatrix() {
        // Return cached result if available
        if (cachedRelationMatrix != null) {
            System.out.println("Using cached relationship matrix");
            return cachedRelationMatrix;
        }

        displayProgress("Building relationship matrix...");
        
        Map<Long, Map<Long, Integer>> relationMap = relationBetweenDocs();
        
        // Find the maximum document ID to determine matrix size
        long maxDocId = 0;
        for (Map.Entry<Long, Map<Long, Integer>> entry : relationMap.entrySet()) {
            maxDocId = Math.max(maxDocId, entry.getKey());
            for (Long childId : entry.getValue().keySet()) {
                maxDocId = Math.max(maxDocId, childId);
            }
        }
        
        // Create matrix with proper size (+1 because IDs start at 0)
        int size = (int) Math.min(maxDocId + 1, 6000); // Cap at 6000 to prevent excessive memory usage
        System.out.println("Creating relationship matrix of size " + size + "x" + size);
        
        // Use a more efficient matrix creation approach
        int[][] matrix = new int[size][];
        int parentCount = 0;
        int totalParents = relationMap.size();
        
        for (Map.Entry<Long, Map<Long, Integer>> parentEntry : relationMap.entrySet()) {
            Long parentId = parentEntry.getKey();
            if (parentId == null || parentId < 0 || parentId >= size)
                continue;
            
            // Create row only when needed (sparse matrix)
            int parentIndex = parentId.intValue();
            if (matrix[parentIndex] == null) {
                matrix[parentIndex] = new int[size];
            }
            
            Map<Long, Integer> children = parentEntry.getValue();
            for (Map.Entry<Long, Integer> childEntry : children.entrySet()) {
                Long childId = childEntry.getKey();
                if (childId == null || childId < 0 || childId >= size)
                    continue;
                matrix[parentIndex][childId.intValue()] = 1;
            }
            
            // Show progress
            parentCount++;
            if (parentCount % 100 == 0 || parentCount == totalParents) {
                displayProgress("Relationship matrix completed");
            }
        }
        
        // Cache the result
        cachedRelationMatrix = matrix;
        
        return matrix;
    }

    /**
     * Clear cached relationship data
     * Call this method after making changes to the document relationships
     */
    public void clearRelationshipCache() {
        cachedRelationMap = null;
        cachedRelationMatrix = null;
        System.out.println("Relationship cache cleared");
    }

    /**
     * Reset database and start a fresh crawl
     * @param thread_num Number of crawler threads to use
     * @return Response indicating operation status
     */
    @PostMapping("/reset-and-crawl")
    public Map<String, Object> resetDatabaseAndCrawl(@RequestParam(required = true, defaultValue = "4") int thread_num) {
        Map<String, Object> response = new HashMap<>();
        
        if (isRunning) {
            response.put("status", "error");
            response.put("message", "Cannot reset database while crawler is running. Stop the crawler first.");
            return response;
        }
        
        try {
            // Count how many documents we'll delete
            long documentCount = documentsRepository.count();
            long relationshipCount = relatedLinksRepository.count();
            
            // Reset the database - delete all documents and relationships
            System.out.println("Deleting all documents and relationships from database...");
            relatedLinksRepository.deleteAll();
            documentsRepository.deleteAll();
            
            // Clear caches
            cachedRelationMap = null;
            cachedRelationMatrix = null;
            
            response.put("status", "success");
            response.put("deletedDocuments", documentCount);
            response.put("deletedRelationships", relationshipCount);
            response.put("message", "Database reset successfully. Starting fresh crawl.");
            
            // Reset counters for fresh crawl
            count.set(0);
            stopFlag = false;
            isRunning = true;
            startTime = System.currentTimeMillis();
            
            // Start crawling with specified number of threads
            threads = new Thread[thread_num];
            for (int i = 0; i < thread_num; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    try {
                        crawl();
                    } catch (Exception e) {
                        System.err.println("Error in crawler thread " + threadId + ": " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        if (getActiveThreadCount() == 0) {
                            isRunning = false;
                        }
                    }
                });
                threads[i].setDaemon(true);
                threads[i].start();
            }
            
            response.put("crawlStatus", "Started with " + thread_num + " threads");
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Error resetting database: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Stop the crawler if it's running
     */
    @PostMapping("/stop")
    public Map<String, Object> stopCrawler() {
        Map<String, Object> response = new HashMap<>();
        
        if (!isRunning) {
            response.put("status", "info");
            response.put("message", "Crawler is not running.");
            return response;
        }
        
        try {
            close();
            response.put("status", "success");
            response.put("message", "Crawler stopped successfully.");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Error stopping crawler: " + e.getMessage());
        }
        
        return response;
    }

    // Add a simple progress method
    private void displayProgress(String message) {
        System.out.println(message);
    }

    // Replace all ProgressBarUtil.startProgress calls with displayProgress
    // Replace all ProgressBarUtil.completeProgress calls with displayProgress
    // Replace all ProgressBarUtil.displayProgressBar calls with simple console output
    // Replace all DocumentRelationshipUtil.displayRelationships calls with simple console output
    // For example:
    // ProgressBarUtil.startProgress("Loading document relationships...") becomes displayProgress("Loading document relationships...")

    /**
     * Calculate how frequently to update the progress bar
     * 
     * @param total Total number of items
     * @param percentInterval How often to update (in percent intervals)
     * @return Number of items between updates
     */
    private int getUpdateFrequency(int total, int percentInterval) {
        if (total <= 0 || percentInterval <= 0 || percentInterval > 100) {
            return 1;
        }
        
        // Calculate items per percentage point
        int itemsPerPercent = Math.max(1, total / 100);
        
        // Return items per update interval
        return Math.max(1, itemsPerPercent * percentInterval);
    }
    
    /**
     * Display statistics about document relationships
     * 
     * @param relationMap Map of document relationships
     * @param topParents Number of top parents to display
     * @param topChildren Number of top children per parent to display
     */
    private void displayRelationships(Map<Long, Map<Long, Integer>> relationMap, int topParents, int topChildren) {
        if (relationMap == null || relationMap.isEmpty()) {
            System.out.println("No document relationships to display.");
            return;
        }
        
        System.out.println("\nDocument Relationship Statistics:");
        System.out.println("================================");
        
        // Count documents
        int totalParents = relationMap.size();
        int totalRelationships = 0;
        for (Map<Long, Integer> childMap : relationMap.values()) {
            totalRelationships += childMap.size();
        }
        
        System.out.println("Total parent documents: " + totalParents);
        System.out.println("Total relationships: " + totalRelationships);
        
        if (totalParents == 0) return;
        
        // Calculate avg children per parent
        double avgChildrenPerParent = (double) totalRelationships / totalParents;
        System.out.printf("Average children per parent: %.2f\n", avgChildrenPerParent);
        
        System.out.println("\nAnalysis complete.");
    }
}