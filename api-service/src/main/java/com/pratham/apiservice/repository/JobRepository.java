package com.pratham.apiservice.repository;

import com.pratham.apiservice.domain.Job;
import com.pratham.apiservice.domain.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    Page<Job> findByStatus(JobStatus status, Pageable pageable);

    Page<Job> findByType(String type, Pageable pageable);

    Page<Job> findByTypeAndStatus(String type, JobStatus status, Pageable pageable);

    @Query("SELECT j.status, COUNT(j) FROM Job j GROUP BY j.status")
    List<Object[]> countByStatus();

    /** Conditional transition: only a job that is still in the DLQ can be re-driven. */
    @Transactional
    @Modifying
    @Query("""
            UPDATE Job j SET j.status = com.pratham.apiservice.domain.JobStatus.QUEUED,
                   j.retryCount = 0, j.nextRetryAt = NULL, j.lastError = NULL,
                   j.workerId = NULL, j.updatedAt = CURRENT_TIMESTAMP
            WHERE j.id = :id AND j.status = com.pratham.apiservice.domain.JobStatus.DLQ
            """)
    int requeueFromDlq(@Param("id") long id);

    @Query("SELECT j.id FROM Job j WHERE j.status = com.pratham.apiservice.domain.JobStatus.DLQ ORDER BY j.id")
    List<Long> findDlqIds(Pageable pageable);

    @Transactional
    @Modifying
    @Query("DELETE FROM Job j WHERE j.status IN :statuses AND j.updatedAt < :cutoff")
    int deleteFinishedBefore(@Param("statuses") List<JobStatus> statuses, @Param("cutoff") Instant cutoff);
}
