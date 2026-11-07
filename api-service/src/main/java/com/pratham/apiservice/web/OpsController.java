package com.pratham.apiservice.web;

import com.pratham.apiservice.service.*;
import com.pratham.apiservice.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** Dashboard-facing endpoints: live stats, DLQ management, runtime settings and chaos. */
@RestController
@RequestMapping("/api")
public class OpsController {

    private final LiveStatsHub hub;
    private final RunStatsService runStats;
    private final JobService jobs;
    private final SettingsService settings;
    private final ChaosService chaos;

    public OpsController(LiveStatsHub hub, RunStatsService runStats, JobService jobs,
                         SettingsService settings, ChaosService chaos) {
        this.hub = hub;
        this.runStats = runStats;
        this.jobs = jobs;
        this.settings = settings;
        this.chaos = chaos;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return hub.latest();
    }

    @GetMapping("/stats/runs/{type}")
    public RunStats run(@PathVariable String type) {
        return runStats.forType(type);
    }

    @PostMapping("/dlq/{id}/redrive")
    public RedriveResponse redrive(@PathVariable long id) {
        return new RedriveResponse(jobs.redrive(id) ? 1 : 0);
    }

    @PostMapping("/dlq/redrive-all")
    public RedriveResponse redriveAll(@RequestParam(defaultValue = "100") int limit) {
        return new RedriveResponse(jobs.redriveAll(limit));
    }

    @GetMapping("/settings")
    public SettingsView getSettings() {
        return new SettingsView(settings.failureRate());
    }

    @PutMapping("/settings")
    public SettingsView updateSettings(@Valid @RequestBody SettingsUpdate update) {
        return new SettingsView(settings.setFailureRate(update.failureRate()));
    }

    @PostMapping("/chaos/crash-worker")
    public CrashResponse crash() {
        return new CrashResponse(chaos.crashRandomWorker());
    }
}
