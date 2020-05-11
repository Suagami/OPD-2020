package spider;

import database.Database;
import logger.LoggerUtils;
import scraper.Statistic;
import scraper.SplashScraper;
import scraper.ScraperConnectionException;
import splash.DefaultSplashRequestFactory;
import splash.SplashNotRespondingException;
import utils.CSVParser;
import utils.Link;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Class carrying out the main work of the program: turn domains into words inside database
 */
public class Spider {
    // in seconds
    private static final int DOMAIN_TIMEOUT = 100;
    // after that number of fails in a row program stops
    private static final int CONNECT_FAILS = 10;

    private final ContextFactory contextFactory;
    private final Database database;
    private final Set<String> scrapedDomains = new HashSet<>();

    private int connectFailsInARowCount = 0;
    private Link domain;

    public Spider(ContextFactory contextFactory, Database database) {
        this.contextFactory = contextFactory;
        this.database = database;
    }

    /**
     * Gets domains from csv file "id";"company_id";"website", extracts words and puts them inside database
     * Ignores repeated domains, domains are separated by host name without www
     *
     * @param input path to CSV file with domains
     * @param output //todo спросить у Никиты или Андрея
     */
    public void scrapeFromCSVFile(String input, String output) {
        var csvParser = new CSVParser();
        csvParser.parse(input);
        List<Link> domains = csvParser.getLinks();
        scrapeDomains(domains);
    }

    /**
     * Extracts words and puts them inside database
     * Ignores repeated domains, domains are separated by host name without www
     *
     * @param domains to be scraped
     */
    public void scrapeDomains(Collection<Link> domains) {
        var domainExec = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService dbExec = Executors.newSingleThreadScheduledExecutor();

        var requestFactory = new DefaultSplashRequestFactory();

        try {
            for (Link d : domains) {
                domain = d;
                if (checkDomainAlreadyWas()) continue;
                var context = contextFactory.createContext();
                var scraper = new SplashScraper(requestFactory);
                Set<String> allWords = ConcurrentHashMap.newKeySet();
                var future = domainExec.submit(() -> new DomainTask(domain, context, scraper, allWords).scrapeDomain());
                handleDomainFuture(future);
                trackStatistic(scraper.getStatistic());
                dbExec.submit(new DatabaseTask(database, domain, allWords)::run);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LoggerUtils.debugLog.error("Spider - Interrupted", e);
        } catch (ScraperConnectionException e) {
            LoggerUtils.debugLog.error("Spider - Stopped, too many connection fails", e);
            LoggerUtils.consoleLog.error("Spider stopped, too many connection fails");
        } catch (ExecutionException e) {
            if (e.getCause().getClass().equals(SplashNotRespondingException.class)) {
                LoggerUtils.debugLog.error("Spider - " + e.getMessage(), e);
                LoggerUtils.consoleLog.error(e.getMessage());
            } else {
                LoggerUtils.debugLog.error("Spider - Failed", e);
            }
        } catch (Exception e) {
            LoggerUtils.debugLog.error("Spider - Failed", e);
        } finally {
            LoggerUtils.debugLog.info("Spider - Completed");
            shutdownExecutorService(dbExec);
            shutdownExecutorService(domainExec);
            SplashScraper.shutdown();
            LoggerUtils.debugLog.info("Spider - Resources were closed");
        }
    }

    private boolean checkDomainAlreadyWas() {
        var fixed = domain.fixWWW().getHost();
        if (scrapedDomains.contains(fixed)) {
            LoggerUtils.debugLog.info("Spider - Skip domain because is it already scraped " + domain);
            LoggerUtils.consoleLog.warn("Skip domain because is it already scraped " + domain);
            return true;
        }
        scrapedDomains.add(fixed);
        return false;
    }

    private void handleDomainFuture(Future<?> future) throws InterruptedException, ExecutionException {
        try {
            future.get(DOMAIN_TIMEOUT, TimeUnit.SECONDS);
            connectFailsInARowCount = 0;
        } catch (TimeoutException e) {
            future.cancel(true);
            LoggerUtils.debugLog.error("Spider - Stopped, waiting too long for scraping site " + domain);
            LoggerUtils.consoleLog.error("Spider stopped, waiting too long for scraping site " + domain);
        } catch (ExecutionException e) {
            var exClass = e.getCause().getClass();
            if (exClass.equals(ScraperConnectionException.class)) {
                LoggerUtils.debugLog.error("DomainTask - Request failed " + domain, e);
                LoggerUtils.consoleLog.error("Request failed " + domain + " " + e.getMessage());
                ++connectFailsInARowCount;
                if (connectFailsInARowCount == CONNECT_FAILS) {
                    throw new ScraperConnectionException("Too many connect fails");
                }
            } else  {
                throw e;
            }
        }
    }

    private void trackStatistic(Statistic statistic) {
        LoggerUtils.consoleLog.info(statistic.toString() + " site "  + domain);
        LoggerUtils.debugLog.info("Spider - " + statistic.toString() + " site "  + domain);
    }

    private void shutdownExecutorService(ExecutorService executorService) {
        executorService.shutdown();
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
