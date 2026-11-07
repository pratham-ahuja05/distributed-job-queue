package com.pratham.apiservice;

import com.pratham.apiservice.entity.Job;
import com.pratham.apiservice.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class JobIntegrationTest {

    @MockBean
    public RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void shouldSaveJobInDatabase() {

        // Arrange
        Job job = new Job();
        job.setType("EMAIL");

        // Act
        Job savedJob = jobRepository.save(job);

        // Assert
        assertNotNull(savedJob.getId());
        assertEquals("EMAIL", savedJob.getType());
    }
}