package com.pratham.workerservice.service;

public interface QueueSelectionStrategy {

    String selectQueue();

    int queueCount();

    String queueAt(int index);
}
