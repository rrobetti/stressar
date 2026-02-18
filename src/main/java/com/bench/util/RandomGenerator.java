package com.bench.util;

import java.util.Random;

/**
 * Thread-safe random number generator wrapper.
 */
public class RandomGenerator {
    private final Random random;
    
    public RandomGenerator(long seed) {
        this.random = new Random(seed);
    }
    
    /**
     * Generate uniform random long in range [min, max].
     */
    public long nextLong(long min, long max) {
        if (min >= max) {
            return min;
        }
        long range = max - min + 1;
        return min + (long) (random.nextDouble() * range);
    }
    
    /**
     * Generate uniform random int in range [min, max].
     */
    public int nextInt(int min, int max) {
        if (min >= max) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }
    
    /**
     * Generate random double in range [0, 1).
     */
    public double nextDouble() {
        return random.nextDouble();
    }
    
    /**
     * Generate random boolean with given probability.
     */
    public boolean nextBoolean(double probability) {
        return random.nextDouble() < probability;
    }
}
