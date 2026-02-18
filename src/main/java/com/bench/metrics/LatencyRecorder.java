package com.bench.metrics;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe latency recorder using HdrHistogram.
 */
public class LatencyRecorder {
    private final Histogram histogram;
    private final long highestTrackableValue;
    private final int numberOfSignificantValueDigits;
    
    /**
     * Create a latency recorder.
     * @param highestTrackableValueMs Maximum latency to track (in milliseconds)
     * @param numberOfSignificantValueDigits Precision (typically 3)
     */
    public LatencyRecorder(long highestTrackableValueMs, int numberOfSignificantValueDigits) {
        this.highestTrackableValue = TimeUnit.MILLISECONDS.toMicros(highestTrackableValueMs);
        this.numberOfSignificantValueDigits = numberOfSignificantValueDigits;
        this.histogram = new Histogram(highestTrackableValue, numberOfSignificantValueDigits);
    }
    
    /**
     * Record a latency value in milliseconds.
     */
    public void record(double latencyMs) {
        long latencyMicros = Math.round(latencyMs * 1000.0);
        try {
            histogram.recordValue(latencyMicros);
        } catch (ArrayIndexOutOfBoundsException e) {
            // Value exceeds highest trackable value
            histogram.recordValue(highestTrackableValue);
        }
    }
    
    /**
     * Record a latency value in nanoseconds.
     */
    public void recordNanos(long latencyNanos) {
        long latencyMicros = TimeUnit.NANOSECONDS.toMicros(latencyNanos);
        try {
            histogram.recordValue(latencyMicros);
        } catch (ArrayIndexOutOfBoundsException e) {
            histogram.recordValue(highestTrackableValue);
        }
    }
    
    /**
     * Get percentile value in milliseconds.
     */
    public double getPercentile(double percentile) {
        long valueMicros = histogram.getValueAtPercentile(percentile);
        return valueMicros / 1000.0;
    }
    
    /**
     * Get maximum latency in milliseconds.
     */
    public double getMax() {
        return histogram.getMaxValue() / 1000.0;
    }
    
    /**
     * Get mean latency in milliseconds.
     */
    public double getMean() {
        return histogram.getMean() / 1000.0;
    }
    
    /**
     * Get total count of recorded values.
     */
    public long getCount() {
        return histogram.getTotalCount();
    }
    
    /**
     * Reset the histogram.
     */
    public void reset() {
        histogram.reset();
    }
    
    /**
     * Create a copy of the current histogram.
     */
    public Histogram copyHistogram() {
        return histogram.copy();
    }
    
    /**
     * Export histogram to HdrHistogram log format.
     */
    public void exportToLog(String filename, long startTime) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(filename);
             PrintStream ps = new PrintStream(fos)) {
            HistogramLogWriter writer = new HistogramLogWriter(ps);
            writer.outputComment("[Benchmark latencies in microseconds]");
            writer.outputLogFormatVersion();
            writer.outputStartTime(startTime);
            writer.outputLegend();
            writer.outputIntervalHistogram(histogram);
        }
    }
}
