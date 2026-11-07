package com.pratham.workerservice;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres + Redis (Testcontainers), started once and shared by every integration test.
 * The schema comes from api-service's Flyway migrations, so tests run against the exact DDL
 * (indexes included) that production uses.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=filesystem:../api-service/src/main/resources/db/migration",
        "worker.auto-start=false",
        "worker.scheduling-enabled=false",
        "worker.instance-id=test-instance",
        "worker.concurrency=4",
        "worker.default-duration-ms=0",
        "worker.retry-base-delay-ms=100",
        "worker.retry-max-delay-ms=1000",
        "worker.stale-after-seconds=5",
        "worker.orphan-after-seconds=5",
        "worker.chaos-enabled=true"
})
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        jdbc.execute("TRUNCATE jobs RESTART IDENTITY");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** Inserts a job row the way api-service would and returns its id. */
    protected long insertJob(String status, int priority, int retryCount, int maxRetries, String payload) {
        return jdbc.queryForObject("""
                INSERT INTO jobs (type, payload, status, priority, retry_count, max_retries)
                VALUES ('test', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, payload, status, priority, retryCount, maxRetries);
    }

    protected long insertQueued(int priority, String payload) {
        return insertJob("QUEUED", priority, 0, 3, payload);
    }

    protected String status(long id) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id);
    }

    protected int intColumn(long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM jobs WHERE id = ?", Integer.class, id);
    }
}
