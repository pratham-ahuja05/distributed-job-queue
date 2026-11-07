package com.pratham.apiservice.web;

import com.pratham.apiservice.IntegrationTestBase;
import com.pratham.apiservice.queue.QueueKeys;
import com.pratham.apiservice.service.LiveStatsHub;
import com.pratham.apiservice.service.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OpsApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    LiveStatsHub hub;

    @Autowired
    RetentionService retention;

    @Autowired
    RedisMessageListenerContainer listenerContainer;

    // ---- DLQ ---------------------------------------------------------------------------

    @Test
    void redriveMovesADeadJobBackToItsQueueWithAFreshRetryBudget() throws Exception {
        long id = insertJob("EMAIL", "DLQ", 2);
        jdbc.update("UPDATE jobs SET retry_count = 3, last_error = 'boom' WHERE id = ?", id);
        redis.opsForList().leftPush(QueueKeys.DLQ, Long.toString(id));

        mvc.perform(post("/api/dlq/" + id + "/redrive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(1));

        assertThat(jdbc.queryForMap("SELECT status, retry_count, last_error FROM jobs WHERE id = ?", id))
                .containsEntry("status", "QUEUED").containsEntry("retry_count", 0).containsEntry("last_error", null);
        assertThat(redis.opsForList().range(QueueKeys.MEDIUM, 0, -1)).containsExactly(Long.toString(id));
        assertThat(redis.opsForList().size(QueueKeys.DLQ)).isZero();

        // second redrive is a no-op: the job is no longer dead
        mvc.perform(post("/api/dlq/" + id + "/redrive")).andExpect(jsonPath("$.requeued").value(0));
        mvc.perform(post("/api/dlq/999/redrive")).andExpect(status().isNotFound());
    }

    @Test
    void redriveAllRequeuesEveryDeadJob() throws Exception {
        insertJob("A", "DLQ", 1);
        insertJob("A", "DLQ", 3);
        insertJob("A", "SUCCESS", 3);

        mvc.perform(post("/api/dlq/redrive-all")).andExpect(jsonPath("$.requeued").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'QUEUED'", Long.class)).isEqualTo(2);
    }

    // ---- settings + chaos --------------------------------------------------------------

    @Test
    void failureRateIsStoredInRedisAndValidated() throws Exception {
        mvc.perform(get("/api/settings")).andExpect(jsonPath("$.failureRate").value(0.0));
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{\"failureRate\":0.25}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failureRate").value(0.25));
        assertThat(redis.opsForValue().get(QueueKeys.FAILURE_RATE_KEY)).isEqualTo("0.25");
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{\"failureRate\":2}"))
                .andExpect(status().isBadRequest());

        redis.opsForValue().set(QueueKeys.FAILURE_RATE_KEY, "garbage");
        mvc.perform(get("/api/settings")).andExpect(jsonPath("$.failureRate").value(0.0));
    }

    @Test
    void crashRequestTargetsALiveWorkerInstanceAndIsRateLimited() throws Exception {
        mvc.perform(post("/api/chaos/crash-worker")).andExpect(status().isNotFound()); // no workers yet

        redis.opsForHash().putAll("worker:w1-t0", Map.of("instance", "w1", "state", "IDLE"));
        List<String> received = new CopyOnWriteArrayList<>();
        listenerContainer.addMessageListener((Message m, byte[] p) ->
                received.add(new String(m.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(QueueKeys.CHAOS_CHANNEL));
        Thread.sleep(300); // let the subscription register

        mvc.perform(post("/api/chaos/crash-worker"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instance").value("w1"));
        mvc.perform(post("/api/chaos/crash-worker")).andExpect(status().isTooManyRequests());

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(received).containsExactly("w1");
    }

    // ---- live stats --------------------------------------------------------------------

    @Test
    void snapshotAggregatesQueuesStatusesWorkersAndEvents() throws Exception {
        insertJob("A", "SUCCESS", 1);
        insertJob("A", "DLQ", 1);
        redis.opsForList().leftPushAll(QueueKeys.HIGH, "1", "2");
        redis.opsForHash().putAll("worker:w1-t0", Map.of("instance", "w1", "state", "BUSY", "jobId", "7"));

        long now = System.currentTimeMillis() - 1000; // last full second
        hub.onMessage(event("{\"type\":\"SUCCESS\",\"jobId\":1,\"latencyMs\":120,\"ts\":" + now + "}"), null);
        hub.onMessage(event("{\"type\":\"SUCCESS\",\"jobId\":2,\"latencyMs\":80,\"ts\":" + now + "}"), null);
        hub.onMessage(event("{\"type\":\"RETRY\",\"jobId\":3,\"ts\":" + now + "}"), null);
        hub.onMessage(event("{\"type\":\"DLQ\",\"jobId\":4,\"ts\":" + now + "}"), null);
        hub.onMessage(event("{\"type\":\"RECOVERED\",\"jobId\":5,\"ts\":" + now + "}"), null);
        hub.onMessage(event("{\"type\":\"SUBMITTED\",\"count\":10,\"ts\":" + now + "}"), null);
        hub.onMessage(event("{\"type\":\"CRASH_REQUESTED\"}"), null);
        hub.onMessage(event("not json"), null);
        hub.tick();

        String body = mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queues.HIGH").value(2))
                .andExpect(jsonPath("$.statuses.SUCCESS").value(1))
                .andExpect(jsonPath("$.statuses.DLQ").value(1))
                .andExpect(jsonPath("$.workers[0].id").value("w1-t0"))
                .andExpect(jsonPath("$.workers[0].state").value("BUSY"))
                .andExpect(jsonPath("$.series", hasSize(60)))
                .andExpect(jsonPath("$.latencyMs.p50").value(80))
                .andExpect(jsonPath("$.latencyMs.p95").value(120))
                .andExpect(jsonPath("$.throughputPerSec").value(0.2))
                .andReturn().getResponse().getContentAsString();

        // Sum over the window: a tick crossing a second boundary shifts which slot holds the events.
        var series = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("series");
        assertThat(sum(series, "success")).isEqualTo(2);
        assertThat(sum(series, "retry")).isEqualTo(1);
        assertThat(sum(series, "dlq")).isEqualTo(1);
        assertThat(sum(series, "recovered")).isEqualTo(1);
        assertThat(sum(series, "submitted")).isGreaterThanOrEqualTo(10); // other tests in this context also submit
    }

    private static long sum(com.fasterxml.jackson.databind.JsonNode series, String field) {
        long total = 0;
        for (var point : series) total += point.get(field).asLong();
        return total;
    }

    @Test
    void streamEndpointOpensAnEventStream() throws Exception {
        hub.tick();
        var result = mvc.perform(get("/api/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:snapshot");
    }

    @Test
    void runStatsComputesPercentilesAndThroughputInPostgres() throws Exception {
        for (int i = 1; i <= 10; i++) {
            jdbc.update("""
                    INSERT INTO jobs (type, status, priority, attempts, retry_count, created_at, started_at, completed_at)
                    VALUES ('run-1', 'SUCCESS', 1, ?, 0, now() - interval '10 seconds',
                            now() - interval '10 seconds' + (? * interval '10 millisecond'),
                            now() - interval '10 seconds' + (? * interval '100 millisecond'))
                    """, i == 10 ? 2 : 1, i, i);
        }
        insertJob("run-1", "DLQ", 2);
        insertJob("run-1", "QUEUED", 2);
        insertJob("other", "SUCCESS", 2);

        mvc.perform(get("/api/stats/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.success").value(10))
                .andExpect(jsonPath("$.dlq").value(1))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.p50LatencyMs", closeTo(550.0, 1.0)))
                .andExpect(jsonPath("$.p95LatencyMs", closeTo(955.0, 1.0)))
                .andExpect(jsonPath("$.reExecutions").value(1))
                .andExpect(jsonPath("$.throughputPerSec", greaterThan(0.0)));

        mvc.perform(get("/api/stats/runs/empty")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void retentionIsDisabledByDefault() {
        long id = insertJob("A", "SUCCESS", 1);
        jdbc.update("UPDATE jobs SET updated_at = now() - interval '1 day' WHERE id = ?", id);
        assertThat(retention.purge()).isZero();
    }

    @Test
    void actuatorExposesPrometheusMetrics() throws Exception {
        hub.tick();
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jobqueue_depth")))
                .andExpect(content().string(containsString("jobs_by_status")));
    }

    private static Message event(String json) {
        return new org.springframework.data.redis.connection.DefaultMessage(
                QueueKeys.EVENTS_CHANNEL.getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));
    }
}
