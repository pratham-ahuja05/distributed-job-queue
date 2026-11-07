# Deploying the live demo

The whole stack runs in about 1.5 GB of RAM, so one small VM is enough:
- **Oracle Cloud Always Free** (Ampere A1, up to 24 GB, free), or
- **Hetzner CX22 / any 2 vCPU, 4 GB VPS** (about €4/month).

PaaS free tiers (Render, Railway) can't run a multi-container Compose app.

```bash
# on the VM (Ubuntu)
curl -fsSL https://get.docker.com | sh
git clone <your-repo-url> && cd distributed-job-queue
export POSTGRES_PASSWORD=$(openssl rand -hex 16) GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 12)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Open port 80 in the VM firewall / security list. For HTTPS, point a domain at the VM
and put Caddy in front (`caddy reverse-proxy --from yourdomain.com --to localhost:80`).

Production overrides: per-IP write rate limit (30 per 10 s), bursts capped at 300,
backlog capped at 5,000, finished jobs deleted after 60 min, and only port 80 exposed.
