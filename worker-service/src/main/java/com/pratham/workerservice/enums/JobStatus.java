package com.pratham.workerservice.enums;

public enum JobStatus {

    QUEUED,        // waiting in Redis
    PROCESSING,    // being worked on
    SUCCESS,       // completed successfully
    FAILED,        // permanently failed (after retries exhausted)
    RETRYING,      // failed but will retry later
    DLQ
}