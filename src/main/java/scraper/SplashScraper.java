package scraper;

import com.google.gson.Gson;
import logger.LoggerUtils;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import spider.FailedSite;
import spider.HtmlLanguageException;
import spider.Site;
import splash.DefaultSplashRequestContext;
import splash.SplashNotRespondingException;
import splash.SplashRequestFactory;
import splash.SplashResponse;
import utils.Html;
import utils.Link;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class SplashScraper implements Scraper {
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build();
    private static final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor();
    //in millis
    private static final int SPLASH_RESTART_TIME = 6000;
    private static final int SPLASH_RETRY_TIMEOUT = 500;
    private static final int SPLASH_IS_UNAVAILABLE_RETRIES = 5;
    private static final Gson gson = new Gson();

    private final Statistic stat = new Statistic();
    private final SplashRequestFactory renderReqFactory;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final List<FailedSite> failedSites = new ArrayList<>();
    private final AtomicInteger scheduledToRetry = new AtomicInteger(0);
    private final AtomicReference<String> domain = new AtomicReference<>();

    public SplashScraper(SplashRequestFactory renderReqFactory) {
        this.renderReqFactory = renderReqFactory;
    }

    public static void shutdown() {
        try {
            retryExecutor.shutdown();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            var cache = httpClient.cache();
            if (cache != null) {
                cache.close();
            }
        } catch (IOException e) {
            LoggerUtils.debugLog.error("SplashScraper - Cache closing rejected", e);
        }
    }

    @Override
    public void scrape(Link link, Consumer<Site> siteConsumer) {
        var request = renderReqFactory.getRequest(new DefaultSplashRequestContext.Builder().setSiteUrl(link).build());
        var call = httpClient.newCall(request);
        calls.add(call);
        call.enqueue(new SplashCallback(new CallContext(link, siteConsumer)));
        stat.requestSended();
    }

    @Override
    public int scrapingSitesCount() {
        // order is important
        return scheduledToRetry.get() + httpClient.dispatcher().runningCallsCount();
    }

    @Override
    public void cancelAll() {
        synchronized (calls) {
            calls.forEach(Call::cancel);
        }
    }

    @Override
    public List<FailedSite> getFailedSites() {
        return failedSites;
    }

    public Statistic getStatistic() {
        return stat;
    }


    private class SplashCallback implements Callback {
        private final Link initialLink;
        private final Consumer<Site> consumer;
        private final CallContext context;
        private Link finalLink;
        private Call call;

        public SplashCallback(CallContext context) {
            this.initialLink = context.getLink();
            this.consumer = context.getConsumer();
            this.context = context;
        }

        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
            this.call = call;
            handleFail(e);
            calls.remove(call);
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) {
            this.call = call;
            try {
                handleResponse(response);
            } catch (Exception e) {
                failedSites.add(new FailedSite(e, initialLink));
                if (e.getClass().equals(HtmlLanguageException.class)) {
                    LoggerUtils.debugLog.error("SplashScraper - Wrong html language "
                                    + initialLink.toString()
                    );
                    stat.responseRejected();
                } else {
                    LoggerUtils.debugLog.error("SplashScraper - Exception while handling response with site "
                                    + initialLink.toString(),
                            e
                    );
                    stat.responseException();
                }
            }
            calls.remove(call);
        }

        private void handleFail(IOException e) {
            var cause = e.getCause();
            if (cause != null && cause.getClass().equals(EOFException.class)) {
                handleSplashRestarting();
                return;
            } else if (cause != null && cause.getClass().equals(SocketException.class)) {
                LoggerUtils.debugLog.error("SplashScraper - Socket is closed " + initialLink);
            } else if (e.getMessage().equals("executor rejected")) {
                LoggerUtils.debugLog.error("SplashScraper - Executor rejected " + initialLink);
                stat.requestFailed();
            } else {
                LoggerUtils.debugLog.error("SplashScraper - Request failed " + initialLink, e);
                stat.requestFailed();
            }
            failedSites.add(new FailedSite(e, initialLink));
        }

        private void handleResponse(Response response) throws IOException {
            int code = response.code();
            if (code == 503 || code == 502) {
                handleSplashRestarting();
            } else if (code == 504) {
                stat.requestTimeout();
                LoggerUtils.debugLog.error("SplashScraper - Timeout expired " + initialLink);
            } else if (code == 200) {
                var responseBody = response.body();
                if (responseBody == null) {
                    throw new ScraperConnectionException("Response body is absent");
                }
                handleResponseBody(responseBody.string());
            }
            stat.requestSucceeded();
            response.close();
        }

        private void handleResponseBody(String responseBody) {
            var splashResponse = gson.fromJson(responseBody, SplashResponse.class);
            finalLink = new Link(splashResponse.getUrl());
            if (!checkDomain()) return;
            checkRedirect();
            if (!call.isCanceled()) {
                consumer.accept(new Site(new Html(splashResponse.getHtml(), finalLink), initialLink));
                stat.siteScraped();
            }
        }

        private boolean checkDomain() {
            if (isDomainSuitable()) {
                return true;
            } else {
                LoggerUtils.debugLog.info(
                        String.format("SplashScraper - Tried to redirect from %s to site %s", initialLink, finalLink)
                );
                stat.responseRejected();
                return false;
            }
        }

        private boolean isDomainSuitable() {
            var scrapedUrlHostWithoutWWW = finalLink.fixWWW().getHost();
            if (domain.get() == null) {
                domain.set(scrapedUrlHostWithoutWWW);
            } else {
                return scrapedUrlHostWithoutWWW.contains(domain.get());
            }
            return true;
        }

        private void checkRedirect() {
            var isRedirected = !finalLink.getWithoutProtocol().equals(initialLink.getWithoutProtocol());
            if (isRedirected) {
                LoggerUtils.debugLog.info(
                        String.format("SplashScraper - Redirect from %s to %s", initialLink, finalLink)
                );
            }
        }

        private void handleSplashRestarting() {
            scheduledToRetry.incrementAndGet();
            var delay = getDelay(context.getRetryCount());
            if (delay == -1) {
                scheduledToRetry.decrementAndGet();
                throw new SplashNotRespondingException();
            } else {
                retryExecutor.schedule(this::retry, delay, TimeUnit.MILLISECONDS);
            }
        }

        private int getDelay(int retryCount) {
            var delay = 0;
            if (retryCount == 0) {
                delay = SPLASH_RESTART_TIME;
            } else if (retryCount < SPLASH_IS_UNAVAILABLE_RETRIES) {
                delay = SPLASH_RETRY_TIMEOUT;
            } else {
                delay = -1;
            }
            return delay;
        }

        private void retry() {
            synchronized (calls) {
                if (!call.isCanceled()) {
                    var newCall = call.clone();
                    calls.add(newCall);
                    newCall.enqueue(new SplashCallback(context.getForNewRetry()));
                }
            }
            scheduledToRetry.decrementAndGet();
            stat.requestRetried();
        }
    }


    private static class CallContext {
        private final Link link;
        private final Consumer<Site> consumer;
        private final int retryCount;

        public CallContext(Link link, Consumer<Site> consumer) {
            this(link, consumer, 0);
        }

        public CallContext(Link link, Consumer<Site> consumer, int retryCount) {
            this.link = link;
            this.consumer = consumer;
            this.retryCount = retryCount;
        }

        public Link getLink() {
            return link;
        }

        public Consumer<Site> getConsumer() {
            return consumer;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public CallContext getForNewRetry() {
            return new CallContext(link, consumer, retryCount + 1);
        }
    }
}
