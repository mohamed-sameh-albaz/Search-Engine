package com.example.searchengine.Crawler.CrawlerMainProcess;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.searchengine.Crawler.Repository.DocumentsRepository;

import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
@RequestMapping("/crawler")
public class CrawlerMainProcess {
    private DocumentsRepository documentsRepository;
    ServeDataBase serveDataBase = new ServeDataBase(documentsRepository);

    // private int seedLinksCount = 0;i n
    @PostMapping
    public void startCrawling(@RequestParam(required = true, defaultValue = "0") int thread_num) {
        Thread threads[] = new Thread[thread_num];
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

        crawl();
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
        Queue<com.example.searchengine.Crawler.Entities.Document> queue = new LinkedList<>();
        Hashtable<String, com.example.searchengine.Crawler.Entities.Document> documents = new Hashtable<>();
        ConcurrentHashMap<String, com.example.searchengine.Crawler.Entities.Document> visitedUrls = new ConcurrentHashMap<>();
        List<com.example.searchengine.Crawler.Entities.Document> crawlers = new ArrayList<>();
        
        synchronized (this) {
            crawlers = serveDataBase.getAllVisited();
        }
        
        int count;
        synchronized (this) {
            count = crawlers.size();
            for (com.example.searchengine.Crawler.Entities.Document doc : crawlers) {
                visitedUrls.put(doc.getUrl(), doc);
                queue.add(doc);
            }
        }
    
        int i = 0;
        while (count < 6000 && i < seedLinksCount) {
            try {
                String seedUrl;
                seedUrl = urlFromSeed[i++];
                if (!visitedUrls.contains(seedUrl)) {
                synchronized (this) {
                        com.example.searchengine.Crawler.Entities.Document doct = new com.example.searchengine.Crawler.Entities.Document();
                        doct.setUrl(seedUrl);
                        visitedUrls.put(doct.getUrl(), doct);
                        queue.add(doct);
                    }
                }
    
                while (queue.size() > 0) {
                    com.example.searchengine.Crawler.Entities.Document docElement;
                    String CurrentURL;
    
                    if (queue.size() == 0 || count >= 6000) break;
                    synchronized (this) {
                        docElement = queue.poll();
                        CurrentURL = docElement.getUrl();
                        CurrentURL = CurrentURL.replace(" ", "%20").replace("\"", "%22").replace(",", "%2C");
                    }
    
                        
                    Document doc;
                    doc = Jsoup.connect(CurrentURL).get();
                    String title = doc.title();
                    String content = doc.html();
                        docElement.setUrl(CurrentURL);
                        docElement.setTitle(title);
                        docElement.setContent(content);
    
                    if (docElement.getStatus() == null) {
                        synchronized (this) {
                            docElement.setStatus("visited");
                            serveDataBase.saveToDatabase(docElement);
                            System.out.println("added to data base " + docElement.getUrl());
                            count++;
                        }
                    }
    
                    URI parentUrl = new URI(CurrentURL);
                    Elements links = doc.getElementsByTag("a");
    
                    for (Element link : links) {
                        synchronized (this) {
                        String finalUrl = null;
                        String Linkurl = link.attr("href");
                        Linkurl = Linkurl.replace(" ", "%20").replace("\"", "%22").replace(",", "%2C");
                        URI resolvedUrI = new URI(Linkurl);
                        resolvedUrI = parentUrl.resolve(resolvedUrI);
                        finalUrl = Normalization(resolvedUrI);
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
                                serveDataBase.saveToDatabase(docSub);
                                visitedUrls.put(finalUrl, docSub);
                                queue.add(docSub);
                                count++;  // also increment count here
                                }
                            
                            else
                            {
                                continue; // Skip to the next iteration if the URL is already visited
                            }
                        }
                        }
                        
                    }
                }
    
            catch (Exception ex) {
                System.out.println("there is an ERROR" + ex);
            }
            System.out.println("Crawling completed for URL: https://www.wikipedia.com");
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

    public boolean checkForAllCrawled(Hashtable<String, com.example.searchengine.Crawler.Entities.Document> documents) {
        try {
            for (Map.Entry<String, com.example.searchengine.Crawler.Entities.Document> entry : documents.entrySet()) {
                String url = entry.getKey();
                com.example.searchengine.Crawler.Entities.Document docElement = entry.getValue();
                Document doc = Jsoup.connect(url).get();
                Elements links = doc.getElementsByTag("a");
                int count = 0;
                for (Element link : links) {
                    String Linkurl = link.attr("href");
                    com.example.searchengine.Crawler.Entities.Document docElement1 = documents.get(Linkurl);
                    if (docElement1 != null && docElement1.getStatus().equals("visited")) {
                        System.out.println("Already visited: " + Linkurl);
                        return false;
                    } else if (docElement1 == null) {
                        count++;
                    }
                }
                if (count == links.size()) {
                    return false;
                } else {
                    docElement.setStatus("Crawled");
                    serveDataBase.updateDocument(docElement);
                }
            }
        } catch (Exception ex) {
            System.out.println("there is an ERROR in checking" + ex);
        }
        return true;
    }
}
