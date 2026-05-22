# AWS Deployment Checklist (20 Concurrent Users)

## 1. Recommended Topology

- `ALB` (HTTPS via ACM certificate)
- `ECS Fargate` service for `backend` and `frontend/nginx` (or split into two services)
- `RDS PostgreSQL` (Multi-AZ recommended)
- `ElastiCache Redis` (single node is enough for early stage, Multi-AZ for higher HA)
- `CloudWatch Logs` + alarms

For your expected load (~20 concurrent users), start with:

- Backend task: `0.5 vCPU`, `1 GB RAM`, desired count `2`
- Frontend/Nginx task: `0.25 vCPU`, `0.5 GB RAM`, desired count `2`
- RDS: `db.t4g.small` or above
- Redis: `cache.t4g.micro` or above

## 2. Mandatory Health Checks

- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`

ALB target group health check recommendation:

- Path: `/actuator/health/readiness`
- Healthy threshold: `2`
- Unhealthy threshold: `3`
- Timeout: `5s`
- Interval: `15s`

## 3. Required Environment Variables

Use values from `.env.example`, and set production-safe values for:

- Secrets:
  - `ADMIN_API_KEY`
  - `ADMIN_JWT_SECRET`
  - `WORKSPACE_JWT_SECRET`
  - `PROVIDER_KEY_ENCRYPTION_KEY`
- Database:
  - `DATABASE_URL`
  - `FLYWAY_URL`
  - `POSTGRES_USER`
  - `POSTGRES_PASSWORD`
- Redis:
  - `REDIS_HOST`
  - `REDIS_PORT`
- Throughput/timeout tuning:
  - `R2DBC_POOL_INITIAL_SIZE`
  - `R2DBC_POOL_MAX_SIZE`
  - `PROVIDER_RESPONSE_TIMEOUT_SECONDS`
  - `PROVIDER_HTTP_MAX_CONNECTIONS`
  - `PROVIDER_HTTP_PENDING_ACQUIRE_MAX_COUNT`
  - `PROVIDER_HTTP_PENDING_ACQUIRE_TIMEOUT_MILLIS`
  - `PROVIDER_HTTP_CONNECT_TIMEOUT_MILLIS`
  - `PROVIDER_HTTP_IDLE_TIMEOUT_SECONDS`

## 4. Network and Security

- Only expose `80/443` on ALB.
- Backend should not be publicly reachable.
- RDS and Redis should be private subnets only.
- Restrict `ADMIN_IP_WHITELIST` to your office/VPN egress addresses.
- Use `AWS Secrets Manager` or `SSM Parameter Store` for secrets.

## 5. Day-1 Ops Baseline

- Enable CloudWatch logs for backend and nginx.
- Add alarms:
  - ALB `5XX` > 1%
  - ECS task restart count
  - RDS CPU > 70% for 5m
  - Redis CPU > 70% for 5m
- Keep at least `2` backend tasks to avoid single-point downtime during deploys.

## 6. Pre-Go-Live Validation

Run a quick local-style concurrency test against your AWS endpoint:

```bash
./scripts/load_test_20_concurrency.sh https://<your-domain>/api/health 200 20
```

Then run a gateway functional test with a real API key:

```bash
curl -sS https://<your-domain>/v1/chat/completions \
  -H "Authorization: Bearer <gateway-api-key>" \
  -H "X-Client-Region: NZ" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","stream":false,"messages":[{"role":"user","content":"say ok"}]}'
```

## 7. Rollback Plan

- Keep previous task definition revision available.
- Use ECS rolling deployment with `minimumHealthyPercent=100`.
- Rollback trigger:
  - Readiness check failing for new tasks
  - ALB 5xx spike
  - Provider timeout/error spike
