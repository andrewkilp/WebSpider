package com.WebSpiderJ;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.ConcurrentLinkedQueue;

public class WebSpider {
    private Set<String> visitedLinks = new HashSet<>();
    private int maxDepth;
    private String[] keyWords;
    private ConcurrentLinkedQueue<String> URLSWithKeyWords = new ConcurrentLinkedQueue<>();
    private Thread writer = new Thread(() -> {
        while (true) {
            if (!URLSWithKeyWords.isEmpty()) {
                try (FileWriter fw = new FileWriter(new File("WebSites.txt"), true)) { //replace file name with whatever you want it to write to
                    fw.write(String.format("%s%n", URLSWithKeyWords.poll()));
                    Thread.sleep(100);
                } catch (IOException ex) {
                    ex.printStackTrace();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    });
    public WebSpider(int maxDepth, String URL, String[] keyWords) {
        this.keyWords = keyWords;
        this.maxDepth = maxDepth;
        writer.start();
        crawl(0, URL);
    }

    void crawl(int depth, String URL) {
        if (depth >= maxDepth || visitedLinks.contains(URL))
            return;
        visitedLinks.add(URL);
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build()).build();) {
            HttpGet request = new HttpGet(URL);
            HttpResponse response = httpClient.execute(request);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                Document doc = Jsoup.parse(entity.getContent(), "UTF-8", URL);
                Elements links = doc.select("a[href]");
                if (doc.hasText()) {
                    new Thread(new WordChecker(doc, URL)).start();
                    ;
                }
                for (Element link : links) {
                    String nextLink = link.absUrl("href");
                    if (nextLink.startsWith("http")) {
                        new Thread(() -> {
                            crawl(depth + 1, nextLink);
                        }).start();
                    }
                }
            }
        } catch (IOException ex) {
            System.out.println("failed to connect to " + URL);
        } catch (IllegalArgumentException ex) {

        }
    }
    private class WordChecker implements Runnable {
        Document doc;
        String URL;
        public WordChecker(Document doc, String URL) {
            this.doc = doc;
            this.URL = URL;
        }
        public void run() {
            Elements elements = doc.getAllElements();
            for (Element element : elements) {
                for (TextNode textNode : element.textNodes()) {
                    String text = textNode.text().trim();
                    for (String keyWord : keyWords) {
                        if (text.contains(keyWord)) {
                            URLSWithKeyWords.add(String.format("%s contains %s%n", URL, keyWord));
                            System.out.println(String.format("%s contains %s", URL, keyWord));
                        }
                    }
                }
            }
        }
    }
    public static void main(String[] args) {
        new WebSpider(50, "https://en.wikipedia.org/wiki/Sorting_algorithm", new String[] { "Monkey" });
    }
}