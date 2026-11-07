package com.pratham.apiservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pratham.apiservice.config.AppProperties;
import com.pratham.apiservice.domain.Job;
import com.pratham.apiservice.domain.JobStatus;
import com.pratham.apiservice.domain.Priority;
import com.pratham.apiservice.queue.JobQueue;
import com.pratham.apiservice.repository.JobRepository;
import com.pratham.apiservice.service.ApiExceptions.NotFoundException;
import com.pratham.apiservice.service.ApiExceptions.TooManyRequestsException;
import com.pratham.apiservice.web.dto.Dtos.BatchRequest;
import com.pratham.apiservice.web.dto.Dtos.BatchResponse;
import com.pratham.apiservice.web.dto.Dtos.CreateJobRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class JobService {

    static final int MAX_PAYLOAD_CHARS = 4096;

    private final JobRepository repository;
    private final JobQueue queue;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final EventPublisher events;
    private final MeterRegistry meters;

    public JobService(JobRepository repository, JobQueue queue, TransactionTemplate tx,
                      ObjectMapper mapper, AppProperties props, EventPublisher events,
                      MeterRegistry meters) {
        this.repository = repository;
        this.queue = queue;
        this.tx = tx;
        this.mapper = mapper;
        this.props = props;
        this.events = events;
        this.meters = meters;
    }

    public Job create(CreateJobRequest req) {
        ensureBacklogRoom(1);
        String payload = toJson(req.payload());
        Job job = Job.builder()
                .type(req.type())
                .payload(payload)
                .status(JobStatus.QUEUED)
                .priority(req.priority().level())
                .maxRetries(req.maxRetries() == null ? 3 : req.maxRetries())
                .build();

        // save() commits before we push the ID, so workers never see an ID whose row is invisible.
        Job saved = repository.save(job);
        queue.enqueue(req.priority(), saved.getId());
        countSubmitted(req.priority(), 1);
        events.publish("SUBMITTED", Map.of("jobId", saved.getId(), "priority", req.priority().level(), "count", 1));
        return saved;
    }

    public BatchResponse createBatch(BatchRequest req) {
        if (req.count() > props.maxBatchSize()) {
            throw new IllegalArgumentException("count must be <= " + props.maxBatchSize());
        }
        ensureBacklogRoom(req.count());

        Map<String, Object> sim = new LinkedHashMap<>();
        if (req.durationMs() != null) sim.put("durationMs", req.durationMs());
        if (req.failRate() != null) sim.put("failRate", req.failRate());
        String payload = toJson(sim.isEmpty() ? null : sim);
        String type = req.type() == null || req.type().isBlank() ? "demo" : req.type();
        int maxRetries = req.maxRetries() == null ? 3 : req.maxRetries();

        List<Job> jobs = new ArrayList<>(req.count());
        for (int i = 0; i < req.count(); i++) {
            Priority p = req.priority() != null ? req.priority() : randomMixedPriority();
            jobs.add(Job.builder().type(type).payload(payload).status(JobStatus.QUEUED)
                    .priority(p.level()).maxRetries(maxRetries).build());
        }

        List<Job> saved = tx.execute(status -> repository.saveAll(jobs));
        Map<Priority, List<Long>> byPriority = new EnumMap<>(Priority.class);
        for (Job j : Objects.requireNonNull(saved)) {
            byPriority.computeIfAbsent(Priority.ofLevel(j.getPriority()), k -> new ArrayList<>()).add(j.getId());
        }
        queue.enqueueAll(byPriority);
        byPriority.forEach((p, ids) -> countSubmitted(p, ids.size()));
        events.publish("SUBMITTED", Map.of("count", saved.size(), "jobType", type));

        return new BatchResponse(saved.size(), saved.get(0).getId(), saved.get(saved.size() - 1).getId());
    }

    public Job get(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("job " + id + " not found"));
    }

    public Page<Job> list(JobStatus status, String type, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "id"));
        if (status != null && type != null) return repository.findByTypeAndStatus(type, status, pageable);
        if (status != null) return repository.findByStatus(status, pageable);
        if (type != null) return repository.findByType(type, pageable);
        return repository.findAll(pageable);
    }

    /** Move one dead-lettered job back to its queue with a fresh retry budget. */
    public boolean redrive(long id) {
        Job job = get(id);
        boolean requeued = repository.requeueFromDlq(id) == 1;
        queue.removeFromDlq(id);
        if (requeued) {
            Priority p = Priority.ofLevel(job.getPriority());
            queue.enqueue(p, id);
            events.publish("REDRIVEN", Map.of("jobId", id, "priority", p.level()));
        }
        return requeued;
    }

    public int redriveAll(int limit) {
        int count = 0;
        for (Long id : repository.findDlqIds(PageRequest.of(0, Math.min(Math.max(limit, 1), 1000)))) {
            if (redrive(id)) count++;
        }
        return count;
    }

    private void ensureBacklogRoom(int incoming) {
        if (queue.pendingTotal() + incoming > props.maxBacklog()) {
            throw new TooManyRequestsException("queue backlog is full (" + props.maxBacklog() + "), try again shortly");
        }
    }

    private Priority randomMixedPriority() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 20) return Priority.HIGH;
        if (r < 50) return Priority.MEDIUM;
        return Priority.LOW;
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null) return null;
        try {
            String json = mapper.writeValueAsString(payload);
            if (json.length() > MAX_PAYLOAD_CHARS) {
                throw new IllegalArgumentException("payload must be <= " + MAX_PAYLOAD_CHARS + " characters of JSON");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not serialisable", e);
        }
    }

    private void countSubmitted(Priority p, int n) {
        meters.counter("jobs_submitted_total", "priority", p.name()).increment(n);
    }
}
