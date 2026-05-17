# Token Relay Gateway

面向 supported countries 的 LLM API 中转站 MVP，支持 BYOK、企业内部 API Gateway、模型路由、限流、配额、审计和多 provider fallback。

## Stack

- Backend: Java 17, Spring Boot 3, WebFlux, PostgreSQL, Redis, Flyway
- Frontend: React, TypeScript, Vite
- Deployment: Docker Compose, Nginx, optional Caddy HTTPS

## Quick Start

```bash
cp .env.example .env
docker compose up --build
```

服务启动后：

- Admin UI: http://localhost:8080
- Gateway API: http://localhost:8080/v1/chat/completions
- Backend health: http://localhost:8080/api/health

默认管理员密钥在 `.env` 的 `ADMIN_API_KEY`。管理 API 需要请求头：

```http
X-Admin-Key: change-me-admin-key
```

用户调用网关需要请求头：

```http
Authorization: Bearer <gateway-api-key>
X-Client-Region: NZ
```

## API Examples

OpenAI-compatible chat completions:

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer demo-user-key" \
  -H "X-Client-Region: NZ" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "stream": true,
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

## MVP Coverage

- API key authentication with hash-at-rest
- Admin APIs for users, gateway API keys, provider keys, usage summary, audit logs
- Region allow-list check
- Redis fixed-window rate limiting
- Monthly token quota reservation and usage counters
- Request normalization for `/v1/chat/completions`
- Provider routing by model prefix and priority
- Fallback across OpenAI, Anthropic, Azure OpenAI, Gemini-compatible endpoints
- Streaming and non-streaming proxy via Spring WebFlux
- Flyway migrations and seed data
- React admin dashboard

## Production Notes

- Replace demo keys and `ADMIN_API_KEY`.
- Terminate HTTPS with Caddy or managed load balancer.
- Restrict admin routes with SSO/VPN in production.
- Store provider keys in a KMS-backed secret store if compliance requires it.
- Add legal terms that require callers to be in provider-supported regions and to comply with provider policies.
