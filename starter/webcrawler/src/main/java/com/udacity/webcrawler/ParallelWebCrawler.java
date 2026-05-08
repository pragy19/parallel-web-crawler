package com.udacity.webcrawler;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {

    private final Clock clock;
    private final Duration timeout;
    private final int popularWordCount;
    private final ForkJoinPool pool;
    private final int maxDepth;
    private final List<Pattern> ignoredUrls;
    private final PageParserFactory parserFactory;

    @Inject
    ParallelWebCrawler(
            Clock clock,
            @Timeout Duration timeout,
            @PopularWordCount int popularWordCount,
            @TargetParallelism int threadCount,
            @MaxDepth int maxDepth,
            @IgnoredUrls List<Pattern> ignoredUrls,
            PageParserFactory parserFactory) {

        this.clock = clock;
        this.timeout = timeout;
        this.popularWordCount = popularWordCount;
        this.maxDepth = maxDepth;
        this.ignoredUrls = ignoredUrls;
        this.parserFactory = parserFactory;

        this.pool = new ForkJoinPool(
                Math.min(threadCount, getMaxParallelism()));
    }

    @Override
    public CrawlResult crawl(List<String> startingUrls) {

        // If max depth is 0, do nothing
        if (maxDepth == 0 || startingUrls.isEmpty()) {
            return new CrawlResult.Builder()
              .setUrlsVisited(0)
              .setWordCounts(new LinkedHashMap<>())
              .build();
        }

        Instant deadline = clock.instant().plus(timeout);

        // Thread-safe visited URL set
        Set<String> visitedUrls = ConcurrentHashMap.newKeySet();

        // Thread-safe word counts
        Multiset<String> wordCounts = ConcurrentHashMultiset.create();

        List<CrawlTask> tasks = startingUrls.stream()
                .map(url -> new CrawlTask(
                        url,
                        deadline,
                        visitedUrls,
                        wordCounts,
                        maxDepth))
                .collect(Collectors.toList());

        // Start crawling
        for (CrawlTask task : tasks) {
            pool.invoke(task);
        }

        // Convert Multiset -> Map
        Map<String, Integer> countMap = new LinkedHashMap<>();

        wordCounts.entrySet().forEach(entry ->
                countMap.put(entry.getElement(), entry.getCount()));

        // Use provided sorting utility
        Map<String, Integer> sortedWordCounts =
                WordCounts.sort(countMap, popularWordCount);

        return new CrawlResult.Builder()
                .setUrlsVisited(visitedUrls.size())
                .setWordCounts(sortedWordCounts)
                .build();
    }

    @Override
    public int getMaxParallelism() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Recursive parallel crawl task.
     */
    private class CrawlTask extends RecursiveAction {

        private final String url;
        private final Instant deadline;
        private final Set<String> visitedUrls;
        private final Multiset<String> wordCounts;
        private final int depth;

        CrawlTask(
                String url,
                Instant deadline,
                Set<String> visitedUrls,
                Multiset<String> wordCounts,
                int depth) {

            this.url = url;
            this.deadline = deadline;
            this.visitedUrls = visitedUrls;
            this.wordCounts = wordCounts;
            this.depth = depth;
        }

        @Override
        protected void compute() {

            // Stop if timeout exceeded
            if (clock.instant().isAfter(deadline)) {
                return;
            }

            // Stop if depth exceeded
            if (depth == 0) {
                return;
            }

            // Ignore matching URLs
            for (Pattern pattern : ignoredUrls) {
                if (pattern.matcher(url).matches()) {
                    return;
                }
            }

            // Prevent duplicate visits
            if (!visitedUrls.add(url)) {
                return;
            }

            // Parse page
            PageParser.Result result =
                    parserFactory.get(url).parse();

            // Add word counts
            result.getWordCounts().forEach(
                    (word, count) -> wordCounts.add(word, count));

            // Create child tasks
            List<CrawlTask> subtasks =
                    result.getLinks()
                            .stream()
                            .map(link -> new CrawlTask(
                                    link,
                                    deadline,
                                    visitedUrls,
                                    wordCounts,
                                    depth - 1))
                            .collect(Collectors.toList());

            // Execute in parallel
            invokeAll(subtasks);
        }
    }
}