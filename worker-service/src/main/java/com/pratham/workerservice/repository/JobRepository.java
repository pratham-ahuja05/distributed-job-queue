package com.pratham.workerservice.repository;

import com.pratham.workerservice.entity.Job;
import com.pratham.workerservice.enums.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    // 🔥 RECOVERY: stuck jobs
    @Query("SELECT j FROM Job j WHERE j.status = :status AND j.updatedAt < :threshold")
    List<Job> findStuckJobs(@Param("status") JobStatus status,
                            @Param("threshold") LocalDateTime threshold);

    // 🔥 RETRY
    List<Job> findByStatusAndNextRetryAtBefore(
            JobStatus status,
            LocalDateTime time
    );

    List<Job> findByStatus(JobStatus status);

    // 💣 CRITICAL: MULTI-WORKER LOCK
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM Job j WHERE j.id = :id")
    Optional<Job> findByIdForUpdate(@Param("id") Long id);
}