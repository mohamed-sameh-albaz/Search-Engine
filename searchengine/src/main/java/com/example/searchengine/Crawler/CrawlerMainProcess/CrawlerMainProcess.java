package com.example.searchengine.Crawler.CrawlerMainProcess;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.searchengine.Crawler.Entities.RelatedLinks;
import com.example.searchengine.Crawler.Entities.RelatedLinksID;
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
    Thread[] threads;

    // CrawlerMainProcess(DocumentsRepository documentsRepository,
    // RelatedLinksRepository relatedLinksRepository) {
    // this.documentsRepository = documentsRepository;
    // this.relatedLinksRepository = relatedLinksRepository;
    // }

    // private int seedLinksCount = 0;i n?
    @PostMapping
    public void startCrawling(@RequestParam(required = true, defaultValue = "0") int thread_num) {
        count.set(0);
        threads = new Thread[thread_num];
        for (int i = 0; i < thread_num; i++) {
            threads[i] = new Thread(() -> crawl());
        }
        for (int i = 0; i < thread_num; i++) {
            threads[i].start();
        }
        for (int i = 0; i < thread_num; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // relationBetweenDocs();
    }

    public String[] ReadseedLinks() {
        try {
            File file = new File("seedlinks.txt");
            BufferedReader Reader = new BufferedReader(new FileReader(file));
            String line = null;
            String[] seedLinks = new String[15];
            for (int i = 0; i < 15; i++) {
                line = Reader.readLine();
                if (line == null) {
                    break;
                }
                seedLinks[i] = line;
            }
            Reader.close();
            return seedLinks;
        } catch (Exception e) {
            System.out.println("Error writing to file: " + e.getMessage());
        }
        return null;
    }

    public void crawl() {
        int seedLinksCount = 15;
        String urlFromSeed[] = ReadseedLinks();
        BlockingQueue<com.example.searchengine.Crawler.Entities.Document> queue = new LinkedBlockingQueue<>();
        Hashtable<String, com.example.searchengine.Crawler.Entities.Document> documents = new Hashtable<>();
        ConcurrentHashMap<String, com.example.searchengine.Crawler.Entities.Document> visitedUrls = new ConcurrentHashMap<>();
        List<com.example.searchengine.Crawler.Entities.Document> crawlers = new ArrayList<>();
        RelatedLinksID relatedLinksID = null;
        RelatedLinks relatedLinks = null;
        Object lockVisitedUrls = new Object();
        Object lockQueue = new Object();
        Object lockDocuments = new Object();

        synchronized (lockDocuments) {
            crawlers = serveDataBase.getAllVisited();
        }
        synchronized (lockVisitedUrls) {
            count.set(crawlers.size());
            for (com.example.searchengine.Crawler.Entities.Document doc : crawlers) {
                visitedUrls.put(doc.getUrl(), doc);
                queue.add(doc);
            }
        }
        System.out.println("count: " + visitedUrls.size());
        int i = 0;
        while (i < seedLinksCount && !Thread.currentThread().isInterrupted() && !stopFlag) {
            String seedUrl;
            seedUrl = urlFromSeed[i++];
            System.out.println("count: " + count);
            if (!visitedUrls.contains(seedUrl)) {
                com.example.searchengine.Crawler.Entities.Document doct = new com.example.searchengine.Crawler.Entities.Document();
                doct.setUrl(seedUrl);
                visitedUrls.put(doct.getUrl(), doct);
                queue.add(doct);
            }

            while (queue.size() > 0) {
                com.example.searchengine.Crawler.Entities.Document docElement;
                String CurrentURL;
                if (count.get() >= MAX_DOCUMENTS) {
                    close();
                    return;
                }
                if (queue.size() == 0)
                    break;
                docElement = queue.poll();
                CurrentURL = docElement.getUrl();
                CurrentURL = CurrentURL.replace(" ", "%20").replace("\"", "%22").replace(",", "%2C");
                try {
                    Document doc;
                    doc = Jsoup.connect(CurrentURL).get();
                    String title = doc.title();
                    String content = doc.html();
                    docElement.setUrl(CurrentURL);
                    docElement.setTitle(title);
                    docElement.setContent(content);

                    if (docElement.getStatus() == null) {
                        docElement.setStatus("visited");
                        serveDataBase.saveToDatabase(docElement);
                        int newcount = count.incrementAndGet();
                        if (newcount >= MAX_DOCUMENTS) {
                            close();
                            return;
                        }
                        System.out.println("added to data base " + docElement.getUrl());

                    }

                    URI parentUrl = new URI(CurrentURL);
                    Elements links = doc.getElementsByTag("a");

                    for (Element link : links) {
                        String finalUrl = null;
                        String Linkurl = link.attr("href");
                        Linkurl = Linkurl.replace(" ", "%20").replace("\"", "%22").replace(",", "%2C");
                        URI resolvedUrI = new URI(Linkurl);
                        resolvedUrI = parentUrl.resolve(resolvedUrI);
                        finalUrl = Normalization(resolvedUrI);
                        try {
                            serveDataBase.saveRelatedLinks(parentUrl.toString(), finalUrl);
                        } catch (Exception e) {
                            System.out.println("Error saving related links: " + e.getMessage());
                        }

                        if (!visitedUrls.containsKey(finalUrl) && RobotsCheck.isAllowed(finalUrl)) {

                            System.out.println("Added to visited URLs: " + finalUrl);
                            com.example.searchengine.Crawler.Entities.Document docSub = new com.example.searchengine.Crawler.Entities.Document();

                            doc = Jsoup.connect(finalUrl).get();
                            String titlechild = doc.title();
                            String contentchild = doc.html();

                            docSub.setUrl(finalUrl);
                            docSub.setTitle(doc.title());
                            docSub.setContent(doc.html());
                            docSub.setStatus("visited");
                            docSub.setParentDocId(docElement.getId());
                            try {

                                serveDataBase.saveToDatabase(docSub);
                                int newcount = count.incrementAndGet();
                                if (newcount >= MAX_DOCUMENTS) {
                                    close();
                                    return;
                                }
                                visitedUrls.put(finalUrl, docSub);
                                queue.add(docSub);
                            } catch (Exception e) {
                                System.out.println("Error saving child URL: " + e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    // count.decrementAndGet();
                    System.out.println("Error fetching child URL: " + " - " + e.getMessage());

                }
                if (count.get() >= MAX_DOCUMENTS) {
                    close();
                    return;
                }
            }

        }

        System.out.println("Crawling completed for");

    }

    public void close() {
        if (!stopFlag) {
            stopFlag = true;
            for (int i = 0; i < threads.length; i++) {
                threads[i].interrupt();
            }
        }
    }

    // normalization method to remove the duplicate links and to remove the
    // unnecessary parts of the URL
    public String Normalization(URI url) {
        // Check if the URL is valid and not already processed
        String urlString = url.toString();
        if (url == null || url.getHost() == null) {
            return null;
        }

        String protocol = url.getScheme().toLowerCase();
        String token = url.getHost().toLowerCase();
        String portPart = (url.getPort() == -1 || protocol == "https" || protocol == "http") ? "" : ":" + url.getPort();
        String path = url.getRawPath() == null ? "" : url.getRawPath().toLowerCase();
        String query = url.getRawQuery() == null ? "" : url.getRawQuery();
        String fragment = url.getRawFragment() == null ? "" : "#" + url.getRawFragment();
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
        // edit query
        StringBuilder editedquery = new StringBuilder();
        String[] params = query.split("&");
        Arrays.sort(params);

        for (String param : params) {
            String[] toencode = param.split("=", 2);
            if (param.length() == 2) {
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
        return urlString;
    }

    public Map<Long, Map<Long, Integer>> relationBetweenDocs() {
        Map<Long, Map<Long, Integer>> relationMap = new Hashtable<>(); // Use ConcurrentHashMap for thread safety
        Map<Long, Integer> childDocIdMap = new Hashtable<>();
        List<Object[]> relatedLinksIDs = relatedLinksRepository.getRelatedLinksIDs(); // Fetch related links IDs from
                                                                                      // the database
        for (Object[] row : relatedLinksIDs) {
            Long childDocId = (Long) row[1]; // Assuming the second column is childDocId
            Long parentDocId = (Long) row[0]; // Assuming the first column is parentDocId
            relationMap.computeIfAbsent(parentDocId, k -> new Hashtable<>()).put(childDocId, 1); // Initialize with 1 if
                                                                                                 // not present
        }
        for (Map.Entry<Long, Map<Long, Integer>> entry : relationMap.entrySet()) {
            Long parentDocId = entry.getKey();
            Map<Long, Integer> children = entry.getValue();
            System.out.println("Parent Doc ID: " + parentDocId);
            for (Map.Entry<Long, Integer> childEntry : children.entrySet()) {
                System.out.println("  Child Doc ID: " + childEntry.getKey() + ", Value: " + childEntry.getValue());
            }
        }
        return relationMap; // Return the map containing the relationships between documents
        // Add the relationship to the map

    }

    public int[][] relationMatrix() {
        Map<Long, Map<Long, Integer>> relationMap = relationBetweenDocs(); // Get the relationship map
        int size = 6000;
        int[][] matrix = new int[size][size];
        for (Map.Entry<Long, Map<Long, Integer>> parentEntry : relationMap.entrySet()) {
            Long parentId = parentEntry.getKey();
            if (parentId == null || parentId < 0 || parentId >= size)
                continue;
            Map<Long, Integer> children = parentEntry.getValue();
            for (Map.Entry<Long, Integer> childEntry : children.entrySet()) {
                Long childId = childEntry.getKey();
                if (childId == null || childId < 0 || childId >= size)
                    continue;
                matrix[parentId.intValue()][childId.intValue()] = 1;
            }
        }
        return matrix;
    }
}