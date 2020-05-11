package spider;

import logger.LoggerUtils;
import scraper.Scraper;
import scraper.ScraperConnectionException;
import splash.SplashNotRespondingException;
import utils.Link;

import java.net.ConnectException;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Class that is fully responsible for scrape all pages from domain and put them into {@link DomainTask#resultWords}
 */
public class DomainTask {
    private final Context context;
    private final Link domain;
    private final Scraper scraper;
    private final BlockingQueue<Link> linkQueue = new LinkedBlockingDeque<>();
    private final Set<String> resultWords;
    private int numberOfScrapedLinks = 1;

    /**
     *
     * @param domain to be scraped
     * @param context to be handed to SiteTask
     * @param scraper to scrape
     * @param resultWords to get all words
     */
    DomainTask(Link domain, Context context, Scraper scraper, Set<String> resultWords) {
        this.domain = domain;
        this.context = context;
        this.scraper = scraper;
        this.resultWords = resultWords;
    }

    /**
     * rethrows exception if domain failed, else ignore
     * Scraper gets link and gives html, SiteTask gives words for database and links for Scraper.
     */
    void scrapeDomain() {
        LoggerUtils.debugLog.info("Domain Task - Start executing site " + domain);
        try {
            scrapeFirstLink(domain);
            while (areAllLinksScraped()) {
                checkIfInterrupted();
                scrapeNextLink();
            }
            if (numberOfScrapedLinks == 1) {
                checkIfScraperThrowException();
            }
        } catch (InterruptedException e) {
            handleInterruption();
        } finally {
            LoggerUtils.debugLog.info("Domain Task - Stop executing site " + domain);
        }
    }

    // order is important
    private boolean areAllLinksScraped() {
        return scraper.scrapingSitesCount() != 0 || !linkQueue.isEmpty();
    }

    private void checkIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private void scrapeFirstLink(Link link) {
        scraper.scrape(link, new SiteTask(context, linkQueue, resultWords)::handleSite);
    }

    private void scrapeNextLink() throws InterruptedException {
        var link = linkQueue.poll(500, TimeUnit.MILLISECONDS);
        if (link != null) {
            scraper.scrape(link, new SiteTask(context, linkQueue, resultWords)::handleSite);
            numberOfScrapedLinks++;
        }
    }

    private void checkIfScraperThrowException() {
        var failedSites = scraper.getFailedSites();
        if (!failedSites.isEmpty()) {
            var failedSite = failedSites.get(0);
            if (failedSite != null) {
                var e = failedSite.getException();
                var exClass = e.getClass();
                if (exClass.equals(SplashNotRespondingException.class)) {
                    throw (SplashNotRespondingException) e;
                } else if (exClass.equals(ConnectException.class)) {
                    throw new ScraperConnectionException(e);
                } else if (exClass.equals(ScraperConnectionException.class)) {
                    throw (ScraperConnectionException) e;
                } else if (exClass.equals(HtmlLanguageException.class)) {
                    LoggerUtils.debugLog.warn("DomainTask - Wrong html language, " +
                            "site is not taken into account " + domain
                    );
                    LoggerUtils.consoleLog.warn("Wrong html language, site is not taken into account " + domain);
                } else {
                    LoggerUtils.debugLog.error("DomainTask - Failed", e);
                }
            }
        }
    }

    private void handleInterruption() {
        scraper.cancelAll();
    }
}
