package com.pratham.apiservice.domain;

public enum JobStatus {
    QUEUED,
    PROCESSING,
    RETRYING,
    SUCCESS,
    DLQ;

    public boolean isTerminal() {
        return this == SUCCESS || this == DLQ;
    }
}
