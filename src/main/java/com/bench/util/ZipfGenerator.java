package com.bench.util;

import java.util.Random;

/**
 * Zipf distribution generator for realistic parameter distributions.
 * Thread-safe when each thread uses its own instance.
 */
public class ZipfGenerator {
    private final Random random;
    private final int numElements;
    private final double[] cumulativeProbabilities;
    
    /**
     * Create a Zipf generator.
     * @param numElements Number of elements in the distribution
     * @param alpha Zipf parameter (typically 1.1)
     * @param seed Random seed for deterministic generation
     */
    public ZipfGenerator(int numElements, double alpha, long seed) {
        if (numElements <= 0) {
            throw new IllegalArgumentException("numElements must be positive");
        }
        if (alpha <= 0) {
            throw new IllegalArgumentException("alpha must be positive");
        }
        
        this.random = new Random(seed);
        this.numElements = numElements;
        this.cumulativeProbabilities = new double[numElements];
        
        // Compute cumulative probabilities
        double sum = 0.0;
        for (int i = 1; i <= numElements; i++) {
            sum += 1.0 / Math.pow(i, alpha);
        }
        if (sum <= 0.0 || !Double.isFinite(sum)) {
            throw new IllegalArgumentException("Zipf normalizer must be finite and positive; check alpha");
        }

        double cumulative = 0.0;
        for (int i = 0; i < numElements; i++) {
            double prob = (1.0 / Math.pow(i + 1.0, alpha)) / sum;
            cumulative += prob;
            cumulativeProbabilities[i] = cumulative;
        }
    }
    
    /**
     * Generate next value from Zipf distribution.
     * Returns value in range [1, numElements].
     */
    public int next() {
        double r = random.nextDouble();
        
        // Binary search to find the index
        int low = 0;
        int high = numElements - 1;
        
        while (low < high) {
            int mid = (low + high) / 2;
            if (cumulativeProbabilities[mid] < r) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        
        return low + 1;  // Return 1-indexed value
    }
    
    /**
     * Generate next value as long.
     */
    public long nextLong() {
        return next();
    }
}
