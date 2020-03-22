package main;

import database.Database;
import scraper.DefaultScraper;
import utils.CSVParser;
import utils.Html;
import utils.Link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class WordsExtractor {
    private static BlockingQueue<Link> linkQueue = new ArrayBlockingQueue<>(1000000);
    private static BlockingQueue<Html> HtmlQueue = new ArrayBlockingQueue<>(1000000);
    private static Map<String, Integer> sitesId = new HashMap<>();
    private static Executor EXECUTOR_SERVICE = Executors.newFixedThreadPool(2);

    public static void main(String[] args) {
        List<Link> links = new ArrayList<>();
        var csvParser = new CSVParser();
        Map<String, Integer> domainsIds = csvParser.getDomainsIds();
//        csvParser.parse("src/main/resources/websites_data.csv");
//        links = csvParser.getLinks();
        links.add(new Link("https://jsoup.org/"));
        for (Link link : links) {
            linkQueue.add(link);
            var parser = new SiteParser.Builder(linkQueue,
                    HtmlQueue,
                    Database.newInstance(),
                    link.getDomain()).build();
            var scraper = new DefaultScraper(linkQueue, HtmlQueue);
            EXECUTOR_SERVICE.execute(parser::start);
            EXECUTOR_SERVICE.execute(scraper::start);
        }
    }
}