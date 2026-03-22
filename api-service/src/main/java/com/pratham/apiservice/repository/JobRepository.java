package com.pratham.apiservice.repository;

import com.pratham.apiservice.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {

}