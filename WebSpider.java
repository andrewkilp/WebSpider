package com.WebSpiderJ;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
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
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WebSpider {
    private Set<String> visitedLinks = new HashSet<>();
    private int maxDepth;
    private String[] keyWords;
    private ConcurrentHashMap<String /*Domain*/, ConcurrentHashMap <String/*agent*/, ConcurrentHashMap<String/*ruleType*/, Vector<String>/*ruling*/>>> RobotTxtRules =new ConcurrentHashMap<>();
    private ConcurrentLinkedQueue<String> URLSWithKeyWords = new ConcurrentLinkedQueue<>();
    String agentName = "*"; // defult for Robots.txt
    private ConcurrentLinkedQueue<Thread> sitesToCheck = new ConcurrentLinkedQueue<>();
    private int webSitesPerSecond;
    private boolean limitedRequestsPerSecond = false;
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
    private Thread checkURLs = new Thread(()->{
        while(true) {
            for(int i = 0; i<webSitesPerSecond;i++){
                if (!sitesToCheck.isEmpty()) {
                    sitesToCheck.poll().start();
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
        }
    });
    public WebSpider(int maxDepth, String URL, String[] keyWords, int webSitesPerSecond){
        this.keyWords = keyWords;
        this.maxDepth = maxDepth;
        this.webSitesPerSecond = webSitesPerSecond;

        limitedRequestsPerSecond = true;
        checkURLs.start();
        writer.start();
        crawl(0, URL);
    }

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
            .setDefaultRequestConfig(RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build())
                .build();) 
        {
            handleRobotTxt(URL, httpClient);
            HttpGet request = new HttpGet(URL);
            URL urlForRobotsTxtRef = new URL(URL);
            String host = urlForRobotsTxtRef.getProtocol() + "://" + urlForRobotsTxtRef.getHost();
            ConcurrentHashMap<String, Vector<String>> rules;
            int crawlTime = 0;
            try{
                rules = RobotTxtRules.get(host).get("*");
                try {
                    crawlTime = Integer.parseInt(rules.get("crawl-delay").get(0));
                } catch(NumberFormatException e) {}
                Vector<String> dissallowedDirs = rules.get("disallowed");
                for(String dir: dissallowedDirs){
                    if(URL.equals(host+dir))
                        return;
                }
            } catch (NullPointerException e){}
            scrapeWebsite(request, URL, crawlTime, depth, httpClient);
        } catch (IOException ex) {
            System.out.println("failed to connect to " + URL);
        } 
        catch (IllegalArgumentException ex) {} 
        catch(InterruptedException e){}
        catch(NullPointerException e) {}
    }
    private void scrapeWebsite(HttpGet request, String URL, int crawlTime, int depth, CloseableHttpClient httpClient) throws InterruptedException, UnsupportedOperationException, IOException {
        HttpResponse response = httpClient.execute(request);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                Document doc = Jsoup.parse(entity.getContent(), "UTF-8", URL);
                Elements links = doc.select("a[href]");
                if (doc.hasText()) {
                    new Thread(
                        new WordChecker(doc, URL) // what the bot is scraping for
                    ).start();
                }
                for (Element link : links) {
                    Thread.sleep(crawlTime*1000);
                    String nextLink = link.absUrl("href");
                    if (nextLink.startsWith("http")) {
                        if(!limitedRequestsPerSecond) {
                            new Thread(() -> {
                                crawl(depth + 1, nextLink); // remove +1 so it never ends
                            }).start();
                            return;
                        }
                        sitesToCheck.add(new Thread(() -> {
                            crawl(depth + 1, nextLink); // remove +1 so it never ends
                        }));
                    }
                }
            }
    }
    private void handleRobotTxt(String URL, CloseableHttpClient httpClient) throws ClientProtocolException, IOException { // honestly just a mess of code
        try { //Robots.txt script :/
            URL urlToCheck = new URL(URL);
            String trimmedUrl = urlToCheck.getProtocol() + "://" + urlToCheck.getHost();
            String robotTxtString = trimmedUrl + "/robots.txt";
            if(RobotTxtRules.containsKey(trimmedUrl))
                return;
            ConcurrentHashMap<String, ConcurrentHashMap<String, Vector<String>>> agentRules = new ConcurrentHashMap<String, ConcurrentHashMap<String, Vector<String>>>();
            RobotTxtRules.put(trimmedUrl, agentRules);
            HttpGet checkForRobotsTxt = new HttpGet(robotTxtString);
            HttpResponse requestRobotsTxt = httpClient.execute(checkForRobotsTxt);
            HttpEntity robotsTxt = requestRobotsTxt.getEntity();
            if (robotsTxt != null) {
                Document rbtTxt = Jsoup.parse(robotsTxt.getContent(), "UTF-8", robotTxtString);
                Elements elements = rbtTxt.getAllElements();
                for (Element element : elements) {
                    parseRobot(agentRules, element);
                }
            } 
        }catch(IllegalArgumentException e) {}
    }
    private void parseRobot(ConcurrentHashMap<String, ConcurrentHashMap<String, Vector<String>>> agentRules, Element element) {
        for (TextNode textNode : element.textNodes()) {
            String rules = textNode.text();
            Scanner scanner = new Scanner(rules);
            String currentAgent = "*";
            while(scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if(line.isEmpty() || line.startsWith("#")) 
                    continue;
                String[] partsInText = line.split(":", 2);
                if(partsInText.length < 2)
                    continue;
                String rule = partsInText[0].trim().toLowerCase();
                String val = partsInText[1].trim();
                switch (rule) {
                    case "user-agent" ->{
                        currentAgent = val.toLowerCase();
                        agentRules.putIfAbsent(currentAgent, new ConcurrentHashMap<String, Vector<String>>());
                        agentRules.get(currentAgent).putIfAbsent("disallow", new Vector<>());
                        agentRules.get(currentAgent).putIfAbsent("allow", new Vector<>());
                        agentRules.get(currentAgent).putIfAbsent("crawl-delay", new Vector<>());
                    }
                    case "disallow" ->{
                        agentRules.get(currentAgent).get("disallow").add(val);
                    }
                    case "allow" ->{
                        agentRules.get(currentAgent).get("allow").add(val);
                    }
                    case "crawl-delay" ->{
                        agentRules.get(currentAgent).get("crawl-delay").add(val);
                    }
                }
            }
            scanner.close();
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
                            return;
                        }
                    }
                }
            }
        }
    }
    public static void main(String[] args) {
         /* How to use
            for Limited webSites/second do
                new WebSpider(50, "https://en.wikipedia.org/wiki/Sorting_algorithm", new String[] { "" },100);
            for Unlimited remove the number at the end
        */
    }
}
