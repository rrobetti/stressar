package com.bench.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-query-type latency statistics across a benchmark run.
 * Thread-safe: multiple worker threads may record concurrently.
 */
public class QueryLatencyRecorder {
    private final ConcurrentHashMap<String, LatencyRecorder> recorders = new ConcurrentHashMap<>();

    /**
     * Record a single query execution latency.
     *
     * @param queryName  logical name of the query (e.g. "query_a", "write_transaction")
     * @param latencyNanos latency of the query in nanoseconds
     */
    public void record(String queryName, long latencyNanos) {
        recorders.computeIfAbsent(queryName, k -> new LatencyRecorder(60000, 3))
                 .recordNanos(latencyNanos);
    }

    /**
     * Return the mean latency (in milliseconds) keyed by query name.
     * Only queries that have at least one recorded sample are included.
     */
    public Map<String, Double> getMeanLatenciesByQuery() {
        Map<String, Double> result = new LinkedHashMap<>();
        recorders.forEach((name, recorder) -> {
            if (recorder.getCount() > 0) {
                result.put(name, recorder.getMean());
            }
        });
        return result;
    }

    /**
     * Return the execution count keyed by query name.
     */
    public Map<String, Long> getCountByQuery() {
        Map<String, Long> result = new LinkedHashMap<>();
        recorders.forEach((name, recorder) -> result.put(name, recorder.getCount()));
        return result;
    }

    /**
     * Reset all per-query latency recorders.
     */
    public void reset() {
        recorders.values().forEach(LatencyRecorder::reset);
    }
}
