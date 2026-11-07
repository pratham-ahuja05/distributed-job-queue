package com.pratham.apiservice.service;

import com.pratham.apiservice.config.AppProperties;
import com.pratham.apiservice.domain.JobStatus;
import com.pratham.apiservice.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Keeps the public demo database small by dropping finished jobs after a retention window. */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JobRepository repository;
    private final AppProperties props;

    public RetentionService(JobRepository repository, AppProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public int purge() {
        if (props.retentionMinutes() <= 0) return 0;
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(props.retentionMinutes()));
        int deleted = repository.deleteFinishedBefore(List.of(JobStatus.SUCCESS), cutoff);
        if (deleted > 0) log.info("Retention: deleted {} finished jobs older than {}", deleted, cutoff);
        return deleted;
    }
}
