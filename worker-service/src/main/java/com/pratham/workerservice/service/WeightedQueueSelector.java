package com.pratham.workerservice.service;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

public class WeightedQueueSelector implements QueueSelectionStrategy {

    private final String[] queues;
    private final int[] cumulativeWeights;
    private final int totalWeight;
    private final IntUnaryOperator randomIntGenerator;

    public WeightedQueueSelector(String[] queues, int[] weights) {
        this(queues, weights, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    public WeightedQueueSelector(String[] queues, int[] weights, IntUnaryOperator randomIntGenerator) {
        Objects.requireNonNull(queues, "queues must not be null");
        Objects.requireNonNull(weights, "weights must not be null");
        Objects.requireNonNull(randomIntGenerator, "randomIntGenerator must not be null");

        if (queues.length == 0) {
            throw new IllegalArgumentException("At least one queue is required");
        }
        if (queues.length != weights.length) {
            throw new IllegalArgumentException("queues and weights must have the same size");
        }

        this.queues = Arrays.copyOf(queues, queues.length);
        this.cumulativeWeights = new int[weights.length];
        this.randomIntGenerator = randomIntGenerator;

        int runningTotal = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] < 0) {
                throw new IllegalArgumentException("weights must be non-negative");
            }
            runningTotal += weights[i];
            this.cumulativeWeights[i] = runningTotal;
        }

        if (runningTotal <= 0) {
            throw new IllegalArgumentException("At least one queue weight must be positive");
        }

        this.totalWeight = runningTotal;
    }

    @Override
    public String selectQueue() {
        int randomValue = randomIntGenerator.applyAsInt(totalWeight);
        if (randomValue < 0 || randomValue >= totalWeight) {
            throw new IllegalStateException("Random selector produced invalid value: " + randomValue);
        }
        int index = Arrays.binarySearch(cumulativeWeights, randomValue + 1);
        if (index < 0) {
            index = -index - 1;
        }
        return queues[index];
    }

    @Override
    public int queueCount() {
        return queues.length;
    }

    @Override
    public String queueAt(int index) {
        return queues[index];
    }
}
