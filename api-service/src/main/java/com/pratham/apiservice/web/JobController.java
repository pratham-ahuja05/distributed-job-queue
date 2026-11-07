package com.pratham.apiservice.web;

import com.pratham.apiservice.domain.JobStatus;
import com.pratham.apiservice.service.JobService;
import com.pratham.apiservice.web.dto.Dtos.BatchRequest;
import com.pratham.apiservice.web.dto.Dtos.BatchResponse;
import com.pratham.apiservice.web.dto.Dtos.CreateJobRequest;
import com.pratham.apiservice.web.dto.Dtos.JobView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobView create(@Valid @RequestBody CreateJobRequest request) {
        return JobView.of(jobs.create(request));
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse batch(@Valid @RequestBody BatchRequest request) {
        return jobs.createBatch(request);
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable long id) {
        return JobView.of(jobs.get(id));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) JobStatus status,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        var result = jobs.list(status, type, page, size);
        List<JobView> items = result.getContent().stream().map(JobView::of).toList();
        return Map.of("items", items, "page", result.getNumber(), "size", result.getSize(),
                "totalElements", result.getTotalElements());
    }
}
