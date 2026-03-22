package com.pratham.apiservice.entity;

import com.pratham.apiservice.enums.JobStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    private JobStatus status;

    private Integer priority;

    //  Retry fields
    @Builder.Default
    private Integer retryCount = 0;

    @Builder.Default
    private Integer maxRetries = 3;

    private LocalDateTime nextRetryAt;

    // Audit fields
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    //
    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        if (this.status == null) {
            this.status = JobStatus.QUEUED;
        }

        if (this.retryCount == null) {
            this.retryCount = 0;
        }

        if (this.maxRetries == null) {
            this.maxRetries = 3;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}