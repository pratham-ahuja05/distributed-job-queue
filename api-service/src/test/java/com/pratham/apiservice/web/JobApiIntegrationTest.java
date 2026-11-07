package com.pratham.apiservice.web;

import com.pratham.apiservice.IntegrationTestBase;
import com.pratham.apiservice.queue.QueueKeys;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobApiIntegrationTest extends IntegrationTestBase {

    private org.springframework.test.web.servlet.ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void createPersistsTheJobAndPushesItsIdToTheMatchingPriorityQueue() throws Exception {
        postJson("/api/jobs", """
                {"type":"EMAIL","priority":"HIGH","payload":{"to":"a@b.c"},"maxRetries":2}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.maxRetries").value(2))
                .andExpect(jsonPath("$.payload").value("{\"to\":\"a@b.c\"}"));

        assertThat(redis.opsForList().range(QueueKeys.HIGH, 0, -1)).containsExactly("1");
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = 1", String.class)).isEqualTo("QUEUED");
    }

    @Test
    void numericPrioritiesAndDefaultsAreAccepted() throws Exception {
        postJson("/api/jobs", "{\"type\":\"PDF\",\"priority\":3}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.maxRetries").value(3));
        postJson("/api/jobs", "{\"type\":\"PDF\",\"priority\":\"2\"}")
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
        assertThat(redis.opsForList().size(QueueKeys.LOW)).isEqualTo(1);
        assertThat(redis.opsForList().size(QueueKeys.MEDIUM)).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejectedWith400() throws Exception {
        postJson("/api/jobs", "{\"priority\":\"HIGH\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("type")));
        postJson("/api/jobs", "{\"type\":\"X\",\"priority\":\"URGENT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("HIGH, MEDIUM or LOW")));
        postJson("/api/jobs", "{\"type\":\"X\",\"priority\":7}")
                .andExpect(status().isBadRequest());
        postJson("/api/jobs", "{\"type\":\"X\",\"priority\":\"HIGH\",\"maxRetries\":50}")
                .andExpect(status().isBadRequest());
        postJson("/api/jobs", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed JSON body"));
        postJson("/api/jobs", "{\"type\":\"X\",\"priority\":\"HIGH\",\"payload\":{\"big\":\"" + "x".repeat(5000) + "\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("payload")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Long.class)).isZero();
    }

    @Test
    void batchCreatesMixedPriorityJobsWithSimulationPayload() throws Exception {
        postJson("/api/jobs/batch", "{\"count\":200,\"type\":\"bench\",\"durationMs\":50,\"failRate\":0.3,\"maxRetries\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submitted").value(200))
                .andExpect(jsonPath("$.firstId").value(1))
                .andExpect(jsonPath("$.lastId").value(200));

        long queued = redis.opsForList().size(QueueKeys.HIGH) + redis.opsForList().size(QueueKeys.MEDIUM)
                + redis.opsForList().size(QueueKeys.LOW);
        assertThat(queued).isEqualTo(200);
        assertThat(redis.opsForList().size(QueueKeys.LOW)).isGreaterThan(redis.opsForList().size(QueueKeys.HIGH));
        assertThat(jdbc.queryForObject("SELECT DISTINCT payload FROM jobs", String.class))
                .isEqualTo("{\"durationMs\":50,\"failRate\":0.3}");
        assertThat(jdbc.queryForObject("SELECT DISTINCT max_retries FROM jobs", Integer.class)).isEqualTo(1);
    }

    @Test
    void batchWithFixedPriorityAndNoTypeDefaultsToDemo() throws Exception {
        postJson("/api/jobs/batch", "{\"count\":3,\"priority\":\"HIGH\"}").andExpect(status().isCreated());
        assertThat(redis.opsForList().size(QueueKeys.HIGH)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT DISTINCT type FROM jobs", String.class)).isEqualTo("demo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE payload IS NULL", Long.class)).isEqualTo(3);
    }

    @Test
    void oversizedBatchAndFullBacklogAreRejected() throws Exception {
        postJson("/api/jobs/batch", "{\"count\":501}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("500")));

        postJson("/api/jobs/batch", "{\"count\":500}").andExpect(status().isCreated());
        postJson("/api/jobs/batch", "{\"count\":500}").andExpect(status().isCreated());
        postJson("/api/jobs", "{\"type\":\"X\",\"priority\":\"LOW\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", containsString("backlog")));
    }

    @Test
    void getAndListJobs() throws Exception {
        insertJob("EMAIL", "SUCCESS", 1);
        insertJob("EMAIL", "DLQ", 2);
        insertJob("PDF", "DLQ", 3);

        mvc.perform(get("/api/jobs/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DLQ"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
        mvc.perform(get("/api/jobs/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("99")));
        mvc.perform(get("/api/jobs/abc")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/jobs")).andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value(3)); // newest first
        mvc.perform(get("/api/jobs?status=DLQ")).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/jobs?type=EMAIL")).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/jobs?type=EMAIL&status=DLQ")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/jobs?size=1&page=1")).andExpect(jsonPath("$.items[0].id").value(2));
    }
}
