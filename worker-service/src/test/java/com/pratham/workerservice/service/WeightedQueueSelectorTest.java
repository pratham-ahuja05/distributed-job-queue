package com.pratham.workerservice.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedQueueSelectorTest {

    @Test
    void selectorMatchesConfiguredDistributionWithinTolerance() {
        WeightedQueueSelector selector = new WeightedQueueSelector(
                new String[]{"high", "medium", "low"},
                new int[]{70, 20, 10},
                bound -> ThreadLocalRandom.current().nextInt(bound)
        );

        int highCount = 0;
        int mediumCount = 0;
        int lowCount = 0;
        int trials = 60_000;

        for (int i = 0; i < trials; i++) {
            String selected = selector.selectQueue();
            if ("high".equals(selected)) {
                highCount++;
            } else if ("medium".equals(selected)) {
                mediumCount++;
            } else {
                lowCount++;
            }
        }

        assertRatio(highCount, trials, 0.70, 0.04);
        assertRatio(mediumCount, trials, 0.20, 0.03);
        assertRatio(lowCount, trials, 0.10, 0.02);
    }

    @Test
    void selectorDoesNotStarveLowWeightQueue() {
        WeightedQueueSelector selector = new WeightedQueueSelector(
                new String[]{"high", "medium", "low"},
                new int[]{98, 1, 1},
                bound -> ThreadLocalRandom.current().nextInt(bound)
        );

        int lowCount = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            if ("low".equals(selector.selectQueue())) {
                lowCount++;
            }
        }

        assertTrue(lowCount > 30);
    }

    @Test
    void selectorRejectsInvalidWeights() {
        assertThrows(IllegalArgumentException.class, () ->
                new WeightedQueueSelector(new String[]{"high", "low"}, new int[]{0, 0}));

        assertThrows(IllegalArgumentException.class, () ->
                new WeightedQueueSelector(new String[]{"high", "low"}, new int[]{10, -1}));
    }

    @Test
    void selectorSupportsDeterministicRandomForTests() {
        AtomicInteger counter = new AtomicInteger(0);
        WeightedQueueSelector selector = new WeightedQueueSelector(
                new String[]{"high", "medium", "low"},
                new int[]{2, 1, 1},
                bound -> counter.getAndIncrement() % bound
        );

        assertEquals("high", selector.selectQueue());
        assertEquals("high", selector.selectQueue());
        assertEquals("medium", selector.selectQueue());
        assertEquals("low", selector.selectQueue());
    }

    private void assertRatio(int count, int total, double expected, double tolerance) {
        double ratio = (double) count / total;
        assertTrue(Math.abs(ratio - expected) <= tolerance,
                "ratio " + ratio + " not within tolerance " + tolerance + " for expected " + expected);
    }
}
