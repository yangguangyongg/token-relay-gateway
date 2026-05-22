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
- Gateway API:
  - `http://localhost:8080/v1/chat/completions`
  - `http://localhost:8080/v1/responses`
  - `http://localhost:8080/v1/embeddings`
  - `http://localhost:8080/anthropic/v1/messages`
- Backend health: http://localhost:8080/api/health
- Backend readiness: http://localhost:8080/actuator/health/readiness
- Backend liveness: http://localhost:8080/actuator/health/liveness

管理后台采用 `JWT + RBAC + IP 白名单`。请在 `.env` 中配置：

```bash
ADMIN_JWT_SECRET=<base64-encoded 32-byte secret>
ADMIN_BOOTSTRAP_ADMIN_USERNAME=admin
ADMIN_BOOTSTRAP_ADMIN_PASSWORD=<strong-password>
ADMIN_BOOTSTRAP_VIEWER_USERNAME=viewer
ADMIN_BOOTSTRAP_VIEWER_PASSWORD=<strong-password>
WORKSPACE_JWT_SECRET=<base64-encoded 32-byte secret>
ADMIN_IP_WHITELIST=127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16
```

登录后，管理 API 需要请求头：

```http
Authorization: Bearer <admin-jwt>
```

`Provider` 的上游 API Key 会使用 `AES-GCM` 加密后存储到数据库。请在 `.env` 中配置：

```bash
PROVIDER_KEY_ENCRYPTION_KEY=<base64-encoded 32-byte key>
```

用户调用网关需要请求头：

```http
Authorization: Bearer <gateway-api-key>
X-Client-Region: NZ
```

Anthropic-compatible 客户端也可以使用：

```http
x-api-key: <gateway-api-key>
anthropic-version: 2023-06-01
X-Client-Region: NZ
```

## 高可用与20并发优化（当前版本）

已落地的关键优化项：

- 后端连接池与生命周期：
  - 启用 `R2DBC pool`（默认 `initial=10`, `max=40`）
  - 启用 `graceful shutdown`，避免重启时中断请求
- Provider 调用链路：
  - `WebClient` 使用 Reactor Netty 连接池
  - 可配置 `connect timeout`、`response timeout`、`pending acquire timeout`
  - 降低高并发时连接争抢导致的请求堆积
- Nginx 网关层：
  - 提升 `worker_connections`
  - 上游 keepalive 连接复用
  - 对 streaming 路径关闭代理缓冲，降低流式延迟
- 可观测性与探针：
  - 暴露 `/actuator/health/liveness` 与 `/actuator/health/readiness`
  - 可直接用于 AWS ALB target health check

可配置参数（`.env`）：

- `R2DBC_POOL_INITIAL_SIZE`
- `R2DBC_POOL_MAX_SIZE`
- `PROVIDER_RESPONSE_TIMEOUT_SECONDS`
- `PROVIDER_HTTP_MAX_CONNECTIONS`
- `PROVIDER_HTTP_PENDING_ACQUIRE_MAX_COUNT`
- `PROVIDER_HTTP_PENDING_ACQUIRE_TIMEOUT_MILLIS`
- `PROVIDER_HTTP_CONNECT_TIMEOUT_MILLIS`
- `PROVIDER_HTTP_IDLE_TIMEOUT_SECONDS`

并发压测脚本：

```bash
./scripts/load_test_20_concurrency.sh http://localhost:8080/api/health 200 20
```

## AWS 部署准备（明日可执行）

完整清单见：

- [docs/aws-deployment-checklist.md](/Users/yangguangyong/Documents/Token中转站/docs/aws-deployment-checklist.md)

## Provider 管理与 BYOK

当前版本支持 `OPENAI`、`ANTHROPIC`、`GEMINI`、`AZURE_OPENAI`，并支持平台共享 key 与用户 BYOK 两种模式。

- `ownerUserId = null`：平台共享 Provider Key（Platform Shared）
- `ownerUserId = <user-id>`：用户自有 Provider Key（BYOK）

路由规则：

1. 如果用户存在 `ACTIVE` 的自有 Provider Key，则仅使用该用户自己的 key 池（严格 BYOK）。
2. 如果用户没有可用自有 key，则使用平台共享 key 池。
3. 在同一池内按模型匹配 + `priority` 排序，失败自动 fallback 到下一条。
4. `healthStatus = UNHEALTHY` 的 key 不参与路由。

### Admin Provider API

- `GET /api/admin/provider-keys`：查看所有 provider keys（含 ownerScope、healthStatus）
- `POST /api/admin/provider-keys`：创建 provider key（可传 `ownerUserId`，为空表示平台共享）
- `POST /api/admin/provider-keys/{id}`：更新状态/优先级/归属等
- `POST /api/admin/provider-keys/{id}/check`：检测 key 有效性并写回健康状态

### User BYOK API

用户可用自己的 `Gateway API Key` 自助管理 BYOK：

- `GET /api/me/provider-keys`
- `POST /api/me/provider-keys`
- `POST /api/me/provider-keys/{id}`
- `POST /api/me/provider-keys/{id}/check`

## 用户与组织（Workspace）管理

当前版本新增了“用户邮箱密码登录 + workspace + 成员 RBAC”能力，并保持 Token Relay Admin 后台独立。

- Token Relay Admin：仍然只有平台管理员（你）通过 `/api/admin/auth/login` 登录并做全局配置。
- Workspace Console：业务用户通过邮箱密码登录后管理自己的 workspace。

### Workspace 用户认证

- `POST /api/workspace/auth/register`
  - 入参：`email`, `password`, `displayName`, `workspaceName`
  - 行为：用于个人用户自助注册，创建用户、创建 `PERSONAL` 类型 workspace、写入 OWNER 成员关系并返回 workspace JWT
- `POST /api/workspace/auth/login`
- `GET /api/workspace/auth/me`

### 用户创建与 Workspace 分配

- `POST /api/admin/users`
  - 支持 `provisioningMode`
  - `USER_ONLY`：只创建用户账号，不自动创建 workspace
  - `ADD_TO_WORKSPACE`：创建用户并直接加入现有 workspace
  - `CREATE_WORKSPACE`：创建用户并同时创建新 workspace（可指定 `PERSONAL` / `ORGANIZATION`）
- `POST /api/admin/workspaces`
  - 平台管理员创建一个新的 workspace，并指定初始 owner

当前推荐规则：

- 团队/公司场景：先建 `ORGANIZATION` workspace，再把成员加入该 workspace
- 独立个人场景：走自助注册或管理员创建 `PERSONAL` workspace
- 用户账号和 workspace 已解耦，批量创建用户不会再自动生成一批默认 workspace

### Workspace 与 RBAC

- 角色：`OWNER`, `ADMIN`, `MEMBER`
- 管控规则（当前实现）：
  - `OWNER` / `ADMIN`：可创建 workspace API key、查看 workspace 账单、配置 workspace 模型策略、管理成员
  - `MEMBER`：无上述管理权限

### Workspace 业务接口

- `GET /api/workspace/workspaces`
- `POST /api/workspace/workspaces`
- `GET /api/workspace/workspaces/{workspaceId}/members`
- `POST /api/workspace/workspaces/{workspaceId}/members`
- `GET /api/workspace/workspaces/{workspaceId}/api-keys`
- `POST /api/workspace/workspaces/{workspaceId}/api-keys`
- `GET /api/workspace/workspaces/{workspaceId}/billing?month=YYYY-MM`
- `GET /api/workspace/workspaces/{workspaceId}/model-configs`
- `POST /api/workspace/workspaces/{workspaceId}/model-configs`

### Workspace 模型策略（Model Policy）

可以按 workspace 为模型设置：

- `provider` + `modelPattern`（支持 `gpt-4o-mini` 或 `gpt-4o*`）
- `enabled`
- `maxTokens`

网关调用时会进行 workspace 策略校验：

- 模型被禁用时拒绝请求
- 请求 `max_tokens` 超过 workspace 上限时拒绝请求

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

OpenAI-compatible Responses API:

```bash
curl http://localhost:8080/v1/responses \
  -H "Authorization: Bearer demo-user-key" \
  -H "X-Client-Region: NZ" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "stream": false,
    "input": "Reply with exactly: responses-ok",
    "max_output_tokens": 20
  }'
```

OpenAI-compatible Embeddings:

```bash
curl http://localhost:8080/v1/embeddings \
  -H "Authorization: Bearer demo-user-key" \
  -H "X-Client-Region: NZ" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "text-embedding-3-small",
    "input": "embedding smoke test"
  }'
```

Anthropic-compatible Messages API:

```bash
curl http://localhost:8080/anthropic/v1/messages \
  -H "x-api-key: demo-user-key" \
  -H "anthropic-version: 2023-06-01" \
  -H "X-Client-Region: NZ" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "max_tokens": 20,
    "stream": false,
    "messages": [{"role": "user", "content": "Reply with exactly: anthropic-ok"}]
  }'
```

## 新用户接入流程（SOP）

下面是当前版本可直接执行的一套接入流程，适用于你新增一个客户/团队成员。

1. 管理员登录后台  
   打开 `http://localhost:8080`，使用 `.env` 里的管理员账号登录。

2. 创建用户  
   在 `Users` 面板创建用户时，先选择模式：
   - `Create user only`：只建账号，适合先导入成员
   - `Create user and add to existing workspace`：直接归属到已有团队
   - `Create user and create new workspace`：适合为独立用户或新客户初始化环境
   然后填写：
   - `email`
   - `displayName`
   - `monthlyTokenQuota`（该用户每月 token 配额）

3. 创建或选择 workspace  
   - 团队场景：在 `Workspace` 面板先创建 `ORGANIZATION` workspace，指定初始 owner
   - 独立用户场景：可在创建用户时直接创建 `PERSONAL` workspace

4. 为用户创建网关密钥  
   在 `Gateway API Keys` 面板：
   - 选择用户
   - 选择该 key 所属的 workspace
   - 设置 `name`
   - 设置 `rateLimitPerMinute`
   - 点击创建后会返回一次明文 key（前缀通常为 `tg_`）  
   注意：该明文只在创建当下返回一次，请立即保存给用户。

5. 配置上游 Provider（平台代付或统一出口）  
   在 `Provider Keys` 面板至少添加一条可用 provider：
   - `provider`: `OPENAI` / `ANTHROPIC` / `AZURE_OPENAI` / `GEMINI`
   - `baseUrl`: 例如 OpenAI 用 `https://api.openai.com`
   - `apiKey`: 对应 provider 的密钥
   - `priority`: 路由优先级（数字越小越优先）

5. （建议）配置价格与账单策略  
   - 在 `Model Pricing` 里确认目标模型有价格规则（否则成本会记为 0）。
   - 在 `Billing Policies` 里设置用户预算、告警阈值、是否超预算自动停用。

6. 把调用方式发给用户  
   用户通过以下网关入口调用：
   - URL: `http://localhost:8080/v1/chat/completions`
   - Header:
     - `Authorization: Bearer <gateway-api-key>`
     - `X-Client-Region: <country-code>`（如 `NZ`）
   - Body:
     - `model` 建议与 provider 匹配（如 OpenAI 用 `gpt-*`，Anthropic 用 `claude-*`，Gemini 用 `gemini-*`）

7. 验证是否接入成功  
   - `Usage` / `User Usage Details` 看请求与 token/cost 是否增长
   - `Monthly Bills` 点击 `Generate Draft Bills`，检查该用户月账单是否生成
   - 如需对账，导出 `User Usage Details` 的月度 CSV

### BYOK 说明（当前版本）

当前版本已经支持两种 provider key 归属：

- 平台共享池：`ownerUserId = null`
- 用户 BYOK：`ownerUserId = <user-id>`

严格 BYOK 路由规则如下：

1. 用户存在 `ACTIVE` 的自有 provider key 时，只使用该用户自己的 key 池。
2. 用户不存在可用自有 key 时，回退到平台共享 key 池。
3. 同一池内按模型匹配、健康状态和 `priority` 排序，并支持 fallback。

## MVP Coverage

- API key authentication with hash-at-rest
- Admin login with JWT session and RBAC (`ADMIN`, `VIEWER`)
- Admin IP allow-list check
- Admin APIs for users, gateway API keys, provider keys, usage summary, audit logs
- Workspace user registration/login with email+password and workspace JWT
- Workspace/member management and RBAC (`OWNER`, `ADMIN`, `MEMBER`)
- Workspace-scoped API key management, billing query, and model policy config
- Platform admin APIs to view/update all workspaces, members, and workspace model configs
- Provider management with BYOK ownership, priority, health checks, and fallback
- Real token metering from provider usage fields (with fallback estimation)
- Model pricing table and per-request estimated cost accounting
- Monthly billing lifecycle (`DRAFT`, `CONFIRMED`, `SENT`, `PAID`) and CSV export
- User billing policies (budget threshold alert, optional API key auto-disable, webhook callback)
- Region allow-list check
- Redis fixed-window rate limiting
- Monthly token quota reservation and usage counters
- Protocol compatibility for `/v1/chat/completions`, `/v1/responses`, `/v1/embeddings`, `/anthropic/v1/messages`
- Provider routing by model prefix and priority
- Fallback across OpenAI, Anthropic, Azure OpenAI, Gemini-compatible endpoints
- Streaming and non-streaming proxy via Spring WebFlux
- Flyway migrations and seed data
- React admin dashboard

## Production Notes

- Replace demo keys and `ADMIN_API_KEY`.
- Set strong admin bootstrap passwords and rotate them regularly.
- Narrow `ADMIN_IP_WHITELIST` to office/VPN egress IPs only.
- Set a strong `PROVIDER_KEY_ENCRYPTION_KEY` and rotate keys if this value is changed.
- Terminate HTTPS with Caddy or managed load balancer.
- Restrict admin routes with SSO/VPN in production.
- Store provider keys in a KMS-backed secret store if compliance requires it.
- Add legal terms that require callers to be in provider-supported regions and to comply with provider policies.

## Historical Usage Backfill

如果你是从旧版本升级，并希望把历史 `usage_events` 补齐 `billable_*`、`pricing_rule_id` 和 `estimated_cost_usd`，可执行：

```bash
docker compose exec -T postgres psql -U ${POSTGRES_USER:-token_gateway} -d ${POSTGRES_DB:-token_gateway} -f /dev/stdin < scripts/backfill_usage_events_billing.sql
```

该脚本是幂等的，可重复执行。
