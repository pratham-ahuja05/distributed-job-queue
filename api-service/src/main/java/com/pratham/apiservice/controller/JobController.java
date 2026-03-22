package com.pratham.apiservice.controller;

import com.pratham.apiservice.entity.Job;
import com.pratham.apiservice.service.JobService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public Job createJob(@RequestBody Job job) {

        return jobService.createJob(job);

    }
}