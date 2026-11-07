package com.pratham.apiservice.web;

import com.pratham.apiservice.IntegrationTestBase;
import com.pratham.apiservice.service.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public-demo protections: per-IP write rate limit and data retention. */
@TestPropertySource(properties = {"app.rate-limit-per10s=3", "app.retention-minutes=60"})
class GuardRailsIntegrationTest extends IntegrationTestBase {

    @Autowired
    RetentionService retention;

    @Test
    void writesBeyondTheLimitGet429ButReadsAndOtherClientsDoNot() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/jobs").header("X-Forwarded-For", "1.1.1.1, 10.0.0.1")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"A\",\"priority\":\"LOW\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/jobs").header("X-Forwarded-For", "1.1.1.1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"A\",\"priority\":\"LOW\"}"))
                .andExpect(status().isTooManyRequests());

        mvc.perform(get("/api/jobs").header("X-Forwarded-For", "1.1.1.1")).andExpect(status().isOk());
        mvc.perform(post("/api/jobs").header("X-Forwarded-For", "2.2.2.2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"A\",\"priority\":\"LOW\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void retentionDeletesOnlyOldSuccessfulJobs() {
        long old = insertJob("A", "SUCCESS", 1);
        long recent = insertJob("A", "SUCCESS", 1);
        long oldDead = insertJob("A", "DLQ", 1);
        jdbc.update("UPDATE jobs SET updated_at = now() - interval '2 hours' WHERE id IN (?, ?)", old, oldDead);

        assertThat(retention.purge()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM jobs ORDER BY id", Long.class)).containsExactly(recent, oldDead);
    }
}
