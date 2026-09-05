# High-Throughput Distributed Job Queue

🔥 A fault-tolerant distributed job processing system designed for high-volume asynchronous workloads with strict priority handling.

---

## 📖 Overview
This project implements a distributed background job processing system that ensures reliability under heavy load and worker failures. It solves common issues like task duplication, worker crashes, [...]

---

## 🧠 System Architecture

<p align="center">
  <img src="system_architecture.png" width="80%"/>
</p>

---

## 📸 Screenshots

<p align="center">
  <img src="1.png" width="45%"/>
  <img src="2.png" width="45%"/>
</p>

---

## ✨ Features
- Multi-tier priority queue with starvation-free scheduling  
- Exactly-once job execution using pessimistic locking  
- Heartbeat-based failure detection and automatic recovery  
- Exponential backoff retry strategy for resilient processing  
- Dead letter queue (DLQ) for persistent failures  
- Horizontally scalable worker architecture  
- Dockerized environment for consistent deployment

---

## 🗂️ Repository Structure
api-service/ – exposes REST endpoints to submit and manage jobs  
worker-service/ – distributed worker services that process queued jobs  
.gitignore – Git ignore rules for build and environment files  
1.png – screenshot of system behavior or logs  
2.png – additional system workflow screenshot  
system_architecture.png – high-level architecture diagram of the system  
README.md – project documentation  
docker-compose.yml – orchestrates all services including DB, Redis, API, and workers

---

## 🚀 How It Works
1. A client submits a job via the API with a type, payload, and priority.  
2. The API pushes the job into a Redis queue corresponding to its priority level.  
3. Worker instances continuously poll Redis using a starvation-free strategy.  
4. A worker retrieves a job and locks its database record using `SELECT FOR UPDATE`.  
5. The job is processed and its status is updated in PostgreSQL.  
6. If processing fails, the job is retried using exponential backoff.  
7. If retries exceed limits, the job is moved to the Dead Letter Queue.  
8. A monitor service detects stuck jobs and re-queues them automatically.

---

## 📦 Technologies Used
Java 17, Spring Boot, Spring Data JPA, PostgreSQL, Redis, Docker, Docker Compose, Maven

---

## 🔧 Configuration Options
- `priority` – defines job importance (HIGH, MEDIUM, LOW)  
- `retry.count` – number of retry attempts before failure  
- `retry.backoff` – exponential delay factor (2^n)  
- `job.timeout` – threshold to detect stuck jobs  
- `spring.datasource.*` – database connection settings  
- `spring.redis.*` – Redis connection configuration

---

## 📊 Outputs 
- job_status table – tracks job lifecycle and processing state  
- dead_letter_queue – stores permanently failed jobs for inspection  
- application logs – provide execution trace and debugging insights

---

## 🤝 Contributing
- Add support for new job types with custom processing logic  
- Extend retry strategies (e.g., jitter-based backoff)  
- Implement job batching for improved throughput  
- Introduce priority-based worker pools for dedicated processing  
- Enhance monitoring service with configurable recovery strategies

---

## 📝 Known Limitations
- Redis-based queues are in-memory and may require persistence tuning  
- No built-in observability (metrics/tracing) yet  
- Scaling depends on manual worker provisioning  
- Lack of request-level idempotency may allow duplicate job submissions  
- Recovery timing depends on accurate system clocks across containers

---

## ❤️ Acknowledgements
Built using modern distributed system patterns inspired by real-world job queue architectures and Spring Boot best practices.

---

## 🔧 Things to Improve (Roadmap)
- Add a web-based UI dashboard for job monitoring, retries, and system health  
- Implement production-ready deployment configurations (Kubernetes, Helm charts)  
- Integrate observability tools (Prometheus, Grafana, distributed tracing)  
- Introduce auto-scaling for workers based on queue load
