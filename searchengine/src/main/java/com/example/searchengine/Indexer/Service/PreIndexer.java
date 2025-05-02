package com.example.searchengine.Indexer.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import opennlp.tools.stemmer.PorterStemmer;

@Service
public class PreIndexer {

    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    public static void main(String[] args) {
        // testing
        PreIndexer preIndexer = new PreIndexer();

        String htmlTxt = "<html>\n" + //
                "  <head>\n" + //
                "    <meta charset=\"UTF-8\">\n" + //
                "    <link rel=\"stylesheet\" href=\"style.css\">\n" + //
                "    <style>body { color: red; }</style>\n" + //
                "    <script>alert('hello');</script>\n" + //
                "  </head>\n" + //
                "  <body>\n" + //
                "    <p>Hello, World! 123</p>\n" + //
                "  </body>\n" + //
                "</html>";
        String paragraph = preIndexer.cleanHTML(htmlTxt);
        // System.out.println("Cleaning the html tags: \n" + paragraph);
        System.out.println();
        System.out.println();

        String cleanedContent = preIndexer.fetchAndCleanHTML("https://www.wikipedia.org/");
        // System.out.println("Cleaned Fetched Content: " + cleanedContent);

        List<String> doc = new ArrayList<>();
        doc = preIndexer.tokenize(cleanedContent);
        System.out.println("tokenized text: \n" + doc + "size: " + doc.size());
        System.out.println();
        System.out.println();

        doc = preIndexer.removeStopWords(doc);
        // System.out.println("text after remove stop words: \n" + doc + "size: " +
        // doc.size());
        System.out.println();
        System.out.println();

        doc = preIndexer.Stemming(doc);
        // System.out.println("text after stemming: \n" + doc + "size: " + doc.size());
        System.out.println();
        System.out.println();

        // List<String> stopWords = preIndexer.getStopWords();
        // for (String word : stopWords) {
        // System.out.println(word);
        // }
        // System.out.println(stopWords.size() + " stop word");
        // System.out.println();
        // System.out.println();
    }

    public List<String> getStopWords() {
        List<String> stopWords = new ArrayList<>();
        try (InputStream inputStream = PreIndexer.class.getClassLoader().getResourceAsStream("stopWords.txt")) {
            if (inputStream == null) {
                throw new RuntimeException("Resource file stopWords.txt not found");
            }
            Scanner scanner = new Scanner(inputStream);
            while (scanner.hasNextLine()) {
                String word = scanner.nextLine();
                stopWords.add(word);
            }
            scanner.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
            throw new RuntimeException(e);
        }
        return stopWords;
    }

    public List<String> removeStopWords(List<String> docs) {
        List<String> stopWords = getStopWords();
        docs.removeAll(stopWords);
        docs.removeIf(item -> item == null || item.isEmpty()); // remove empty places in the list
        return docs;
    }

    public String cleanHTML(String paragraph) {

        // Parse the HTML string into a Document object
        Document doc = Jsoup.parse(paragraph);

        // Remove unwanted tags (style, script, meta, link) and keep only the text
        doc.select("style, script, meta, link").remove();

        // Extract the cleaned text (strips all remaining HTML tags)
        String cleanedText = doc.text();

        // Replace all non-alphabetic characters with a space
        cleanedText = cleanedText.replaceAll("[^a-zA-Z]", " ");

        return cleanedText;
    }

    // testing till the crawler is ready and reindexing pages in case of any updates
    public String fetchAndCleanHTML(String url) {
        try {
            Document pageDocument = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "*")
                    .execute()
                    .parse();

            return cleanHTML(pageDocument.html());
        } catch (Exception e) {
            throw new RuntimeException("Error fetching or cleaning page: " + e.getMessage(), e);
        }
    }

    public List<String> tokenize(String text) {
        text = text.toLowerCase();
        List<String> words = new Vector<>();
        // match patterns with one or more chars in text
        Pattern pattern = Pattern.compile("\\w+");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    public List<String> Stemming(List<String> words) {
        PorterStemmer porterStemmer = new PorterStemmer();

        List<String> stemmedWords = new ArrayList<>();
        for (String word : words) {
            String stemmed = porterStemmer.stem(word);
            stemmedWords.add(stemmed);
        }
        return stemmedWords;
    }
}
