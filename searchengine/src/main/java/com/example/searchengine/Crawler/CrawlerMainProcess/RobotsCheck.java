package com.example.searchengine.Crawler.CrawlerMainProcess;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.net.URI;
import java.util.*;
public class RobotsCheck {



    private static final Map<String, Set<String>> disallowedPathsCache = new HashMap<>();
    private static final String USER_AGENT = "MyCrawler"; // Customize this name

    public static boolean isAllowed(String urlStr) {
        try {
            URI uri = new URI(urlStr);
            String host = uri.getHost();
            String protocol = uri.getScheme();
            String robotsUrl = protocol + "://" + host + "/robots.txt";

            // Check cache
            if (!disallowedPathsCache.containsKey(host)) {
                fetchRobotsTxt(host, robotsUrl);
            }

            Set<String> disallowedPaths = disallowedPathsCache.getOrDefault(host, Collections.emptySet());

            // Check if path is disallowed
            for (String path : disallowedPaths) {
                if (uri.getPath().startsWith(path)) {
                    return false;
                }
            }
        } catch (Exception e) {
            System.out.println("robots.txt check failed for " + urlStr + ": " + e.getMessage());
            // Fail-safe: allow crawling if robots.txt can't be checked
            return true;
        }
        return true;
    }

    private static void fetchRobotsTxt(String host, String robotsUrl) {
        Set<String> disallowedPaths = new HashSet<>();
        try {
            Connection.Response response = Jsoup.connect(robotsUrl)
                    .userAgent(USER_AGENT)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout(5000)
                    .execute();

            String body = response.body();
            String[] lines = body.split("\n");
            boolean applies = false;

            for (String line : lines) {
                line = line.trim();

                if (line.toLowerCase().startsWith("user-agent:")) {
                    String agent = line.split(":", 2)[1].trim();
                    applies = agent.equals("*") || agent.equalsIgnoreCase(USER_AGENT);
                }

                if (applies && line.toLowerCase().startsWith("disallow:")) {
                    String path = line.split(":", 2)[1].trim();
                    if (!path.isEmpty()) {
                        disallowedPaths.add(path);
                    }
                }

                if (line.isEmpty()) {
                    applies = false; // reset block on empty line
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to fetch robots.txt from " + robotsUrl + ": " + e.getMessage());
        }
        disallowedPathsCache.put(host, disallowedPaths);
    }
}

