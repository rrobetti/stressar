package com.bench.load;

import com.bench.config.BenchmarkConfig;
import com.bench.config.ConnectionProvider;
import com.bench.config.ConnectionProviderFactory;
import com.bench.config.OjpProvider;
import com.bench.metrics.MetricsCollector;
import com.bench.metrics.MetricsSnapshot;
import com.bench.metrics.SummaryWriter;
import com.bench.metrics.SystemMetricsCollector;
import com.bench.metrics.TimeseriesWriter;
import com.bench.workloads.Workload;
import com.bench.workloads.WorkloadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates a benchmark run with warmup, steady-state, and cooldown phases.
 */
public class BenchmarkRunner {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final BenchmarkConfig config;
    private final String outputDir;

    public BenchmarkRunner(BenchmarkConfig config, String outputDir) {
        this.config = config;
        this.outputDir = outputDir;
    }

    /**
     * Run the benchmark.
     */
    public void run() throws Exception {
        // Create output directory
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        logger.info("Starting benchmark run");
        logger.info("Connection mode: {}", config.getConnectionMode());
        logger.info("Workload: {}", config.getWorkload().getType());
        logger.info("Output directory: {}", outputDir);

        // Create connection provider
        try (ConnectionProvider connectionProvider = ConnectionProviderFactory.createProvider(config)) {
            logger.info("Connection pool size: {}", connectionProvider.getPoolSize());

            // Create workload
            Workload workload = WorkloadFactory.createWorkload(connectionProvider, config);
            logger.info("Workload: {}", workload.getName());

            // Create metrics collector
            MetricsCollector metrics = new MetricsCollector();

            // Create load generator
            LoadGenerator loadGen;
            TrueOpenLoopLoadGenerator trueOpenLoop = null;
            if (config.getWorkload().isOpenLoop()) {
                trueOpenLoop = new TrueOpenLoopLoadGenerator(workload, metrics, config.getWorkload().getTargetRps());
                loadGen = trueOpenLoop;
                logger.info("Load mode: true open-loop (absolute time-based), target RPS: {}",
                        config.getWorkload().getTargetRps());
            } else {
                loadGen = new ClosedLoopLoadGenerator(workload, metrics, config.getWorkload().getConcurrency());
                logger.info("Load mode: closed-loop, concurrency: {}", config.getWorkload().getConcurrency());
            }

            // Run phases
            int warmupSec = config.getWorkload().getWarmupSeconds();
            int durationSec = config.getWorkload().getDurationSeconds();
            int cooldownSec = config.getWorkload().getCooldownSeconds();

            long steadyStateStart;
            Double appCpuMedian;
            Long gcPauseMsTotal;

            try (TimeseriesWriter timeseriesWriter = new TimeseriesWriter(
                    Paths.get(outputDir, "timeseries.csv").toString())) {

                List<MetricsSnapshot> intervalSnapshots = new ArrayList<>();
                try (MetricsSchedulerHandle metricsScheduler = new MetricsSchedulerHandle()) {
                    // Capture metrics at regular intervals
                    // IMPORTANT: Use separate collector for per-interval percentiles
                    MetricsCollector intervalMetrics = new MetricsCollector();
                    metricsScheduler.scheduled().scheduleAtFixedRate(() -> {
                        try {
                            // Get snapshot of interval metrics (includes per-interval histogram)
                            MetricsSnapshot intervalSnapshot = intervalMetrics.getSnapshot();

                            // Also get cumulative snapshot for counting
                            MetricsSnapshot cumulativeSnapshot = metrics.getSnapshot();
                            intervalSnapshots.add(cumulativeSnapshot);

                            long intervalStart = intervalSnapshots.size() > 1
                                    ? intervalSnapshots.get(intervalSnapshots.size() - 2).getTimestampMs()
                                    : metrics.getStartTimeMs();

                            double intervalSeconds = (cumulativeSnapshot.getTimestampMs() - intervalStart) / 1000.0;
                            double intervalAttemptedRps = 0;
                            double intervalAchievedRps = 0;

                            if (intervalSnapshots.size() > 1) {
                                MetricsSnapshot prev = intervalSnapshots.get(intervalSnapshots.size() - 2);
                                long attemptedDelta = cumulativeSnapshot.getAttemptedRequests() - prev.getAttemptedRequests();
                                long completedDelta = cumulativeSnapshot.getCompletedRequests() - prev.getCompletedRequests();
                                intervalAttemptedRps = attemptedDelta / intervalSeconds;
                                intervalAchievedRps = completedDelta / intervalSeconds;
                            }

                            long intervalErrors = intervalSnapshots.size() > 1 ? cumulativeSnapshot.getErrors()
                                    - intervalSnapshots.get(intervalSnapshots.size() - 2).getErrors() : 0;

                            // FIXED: Use per-interval histogram percentiles, not cumulative
                            timeseriesWriter.writeRow(
                                    cumulativeSnapshot.getTimestampMs(),
                                    intervalAttemptedRps,
                                    intervalAchievedRps,
                                    intervalErrors,
                                    intervalSnapshot.getP50(), // Per-interval percentiles
                                    intervalSnapshot.getP95(),
                                    intervalSnapshot.getP99(),
                                    intervalSnapshot.getP999(),
                                    intervalSnapshot.getMax());

                            // Reset interval metrics for next window
                            intervalMetrics.reset();

                        } catch (Exception e) {
                            logger.error("Error collecting interval metrics", e);
                        }
                    }, config.getMetricsIntervalSeconds(), config.getMetricsIntervalSeconds(), TimeUnit.SECONDS);

                    // Warmup phase
                    if (warmupSec > 0) {
                        logger.info("=== Warmup phase: {} seconds ===", warmupSec);
                        loadGen.start();
                        Thread.sleep(warmupSec * 1000L);

                        // Reset metrics after warmup
                        metrics.reset();
                        intervalMetrics.reset();
                        logger.info("Warmup complete, metrics reset");
                    } else {
                        loadGen.start();
                    }

                    // Start in-process system metrics collection for the steady-state phase only
                    try (SystemMetricsCollector sysMetrics = new SystemMetricsCollector()) {
                        // Steady-state phase
                        logger.info("=== Steady-state phase: {} seconds ===", durationSec);
                        steadyStateStart = System.currentTimeMillis();
                        Thread.sleep(durationSec * 1000L);

                        // Stop load
                        loadGen.stop();

                        // Cooldown phase
                        if (cooldownSec > 0) {
                            logger.info("=== Cooldown phase: {} seconds ===", cooldownSec);
                            Thread.sleep(cooldownSec * 1000L);
                        }

                        appCpuMedian = sysMetrics.getAppCpuMedian();
                        gcPauseMsTotal = sysMetrics.getGcPauseMsTotal();
                    }
                }
            }

            // Get final metrics
            MetricsSnapshot finalSnapshot = metrics.getSnapshot();

            // Populate in-process system metrics fields on the snapshot
            finalSnapshot.setAppCpuMedian(appCpuMedian);
            finalSnapshot.setGcPauseMsTotal(gcPauseMsTotal);

            // Write summary
            SummaryWriter.BenchmarkRunInfo runInfo = new SummaryWriter.BenchmarkRunInfo();
            runInfo.sut = connectionProvider.getModeName();
            runInfo.workload = workload.getName();
            runInfo.loadMode = config.getWorkload().isOpenLoop() ? "open-loop" : "closed-loop";
            runInfo.targetRps = config.getWorkload().getTargetRps();
            runInfo.concurrency = config.getWorkload().getConcurrency();
            runInfo.poolSize = connectionProvider.getPoolSize();
            runInfo.instanceId = config.getInstanceId();
            runInfo.totalInstances = config.getReplicas();
            runInfo.seed = config.getWorkload().getSeed();

            // Add open-loop specific metrics if applicable
            if (trueOpenLoop != null) {
                runInfo.openLoopAttemptedOps = trueOpenLoop.getAttemptedOps();
                runInfo.openLoopMissedOpportunities = trueOpenLoop.getMissedOpportunities();
                runInfo.openLoopSchedulingDelayMs = trueOpenLoop.getSchedulingDelaysNanos() / 1_000_000.0;
            }

            // Add OJP-specific fields if in OJP mode
            if (connectionProvider instanceof OjpProvider) {
                @SuppressWarnings("resource") // closed with connectionProvider in outer try-with-resources
                OjpProvider ojpProvider = (OjpProvider) connectionProvider;
                runInfo.clientPooling = "none";
                runInfo.ojpVirtualConnectionMode = ojpProvider.getOjpConfig().getVirtualConnectionMode().toString();
                runInfo.ojpPoolSharing = ojpProvider.getOjpConfig().getPoolSharing().toString();
                runInfo.ojpPropertiesUsed = ojpProvider.getOjpConfig().getPropertiesForLogging();
                runInfo.clientVirtualConnectionsOpenedTotal = ojpProvider.getVirtualConnectionsOpened();
                runInfo.clientVirtualConnectionsMaxConcurrent = ojpProvider.getVirtualConnectionsMaxConcurrent();
            } else {
                runInfo.clientPooling = "hikari";
            }

            SummaryWriter.SummaryData summary = SummaryWriter.createSummary(finalSnapshot, runInfo);
            SummaryWriter summaryWriter = new SummaryWriter();
            summaryWriter.writeSummary(Paths.get(outputDir, "summary.json").toString(), summary);

            // Export HDR histogram
            metrics.getLatencyRecorder().exportToLog(
                    Paths.get(outputDir, "hdr.hlog").toString(),
                    steadyStateStart);

            logger.info("=== Benchmark complete ===");
            if (logger.isInfoEnabled()) {
                logger.info("Attempted RPS: {}", String.format("%.2f", summary.attemptedRps));
                logger.info("Achieved throughput: {} rps", String.format("%.2f", summary.achievedThroughputRps));
                logger.info("Error rate: {}", String.format("%.4f", summary.errorRate));
                logger.info("Latency p50: {} ms", String.format("%.2f", summary.latencyMs.p50));
                logger.info("Latency p95: {} ms", String.format("%.2f", summary.latencyMs.p95));
                logger.info("Latency p99: {} ms", String.format("%.2f", summary.latencyMs.p99));
            }
            logger.info("Results written to: {}", outputDir);
        }
    }

    /**
     * Owns a single-thread {@link ScheduledExecutorService} and shuts it down on {@link #close()}.
     */
    private static final class MetricsSchedulerHandle implements AutoCloseable {
        private final ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor();

        ScheduledExecutorService scheduled() {
            return scheduled;
        }

        @Override
        public void close() {
            scheduled.shutdown();
            try {
                if (!scheduled.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduled.shutdownNow();
                    if (!scheduled.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Metrics scheduler did not terminate after shutdownNow");
                    }
                }
            } catch (InterruptedException e) {
                scheduled.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
