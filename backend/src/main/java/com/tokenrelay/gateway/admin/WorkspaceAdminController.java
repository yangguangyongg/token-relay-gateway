package com.tokenrelay.gateway.admin;

import com.tokenrelay.gateway.config.AdminSecurityWebFilter;
import com.tokenrelay.gateway.domain.GatewayUser;
import com.tokenrelay.gateway.domain.Workspace;
import com.tokenrelay.gateway.domain.WorkspaceMembership;
import com.tokenrelay.gateway.domain.WorkspaceModelConfig;
import com.tokenrelay.gateway.repository.GatewayUserRepository;
import com.tokenrelay.gateway.repository.WorkspaceMembershipRepository;
import com.tokenrelay.gateway.repository.WorkspaceModelConfigRepository;
import com.tokenrelay.gateway.repository.WorkspaceRepository;
import com.tokenrelay.gateway.service.AuditService;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.WorkspaceAccessService;
import com.tokenrelay.gateway.workspace.WorkspaceRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/workspaces")
public class WorkspaceAdminController {
  private final WorkspaceRepository workspaces;
  private final WorkspaceMembershipRepository memberships;
  private final WorkspaceModelConfigRepository modelConfigs;
  private final GatewayUserRepository users;
  private final WorkspaceAccessService workspaceAccessService;
  private final DatabaseClient databaseClient;
  private final AuditService auditService;

  public WorkspaceAdminController(
      WorkspaceRepository workspaces,
      WorkspaceMembershipRepository memberships,
      WorkspaceModelConfigRepository modelConfigs,
      GatewayUserRepository users,
      WorkspaceAccessService workspaceAccessService,
      DatabaseClient databaseClient,
      AuditService auditService) {
    this.workspaces = workspaces;
    this.memberships = memberships;
    this.modelConfigs = modelConfigs;
    this.users = users;
    this.workspaceAccessService = workspaceAccessService;
    this.databaseClient = databaseClient;
    this.auditService = auditService;
  }

  @GetMapping
  public Flux<Map<String, Object>> listWorkspaces(ServerWebExchange exchange) {
    return databaseClient.sql("""
        SELECT
          w.id::text AS workspace_id,
          w.name AS workspace_name,
          w.slug AS workspace_slug,
          w.type AS workspace_type,
          w.status AS workspace_status,
          w.created_at AS created_at,
          coalesce(m.member_count, 0) AS member_count,
          coalesce(k.active_key_count, 0) AS active_key_count
        FROM workspaces w
        LEFT JOIN (
          SELECT workspace_id, count(*) AS member_count
          FROM workspace_memberships
          WHERE status = 'ACTIVE'
          GROUP BY workspace_id
        ) m ON m.workspace_id = w.id
        LEFT JOIN (
          SELECT workspace_id, count(*) AS active_key_count
          FROM api_keys
          WHERE status = 'ACTIVE'
          GROUP BY workspace_id
        ) k ON k.workspace_id = w.id
        ORDER BY w.created_at DESC
        """)
        .fetch()
        .all();
  }

  @PostMapping
  public Mono<Workspace> createWorkspace(
      ServerWebExchange exchange,
      @Valid @RequestBody CreateWorkspaceRequest request) {
    String actor = currentAdmin(exchange).username();
    return users.findById(request.parsedOwnerUserId())
        .switchIfEmpty(Mono.error(new GatewayException(404, "owner_user_not_found", "ownerUserId does not exist")))
        .flatMap(ownerUser -> workspaces.save(workspaceAccessService.newWorkspace(
                request.name(),
                ownerUser.id(),
                request.type()))
            .flatMap(workspace -> memberships.save(workspaceAccessService.newMembership(
                    workspace.id(),
                    ownerUser.id(),
                    WorkspaceRole.parse(normalizeRole(request.ownerRole(), "OWNER"))))
                .then(auditService.log(actor, "ADMIN_CREATE_WORKSPACE", workspace.id().toString(), workspace.name()))
                .thenReturn(workspace)));
  }

  @GetMapping("/{workspaceId}/members")
  public Flux<Map<String, Object>> members(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId) {
    return workspaceAccessService.requireActiveWorkspace(workspaceId)
        .thenMany(databaseClient.sql("""
            SELECT
              wm.id::text AS membership_id,
              wm.workspace_id::text AS workspace_id,
              wm.user_id::text AS user_id,
              u.email AS user_email,
              u.display_name AS user_name,
              wm.role AS role,
              wm.status AS status,
              wm.created_at AS created_at
            FROM workspace_memberships wm
            JOIN gateway_users u ON u.id = wm.user_id
            WHERE wm.workspace_id = :workspace_id
            ORDER BY wm.created_at ASC
            """)
            .bind("workspace_id", workspaceId)
            .fetch()
            .all());
  }

  @PostMapping("/{workspaceId}/members")
  public Mono<WorkspaceMembership> upsertMember(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId,
      @Valid @RequestBody UpsertMemberRequest request) {
    String actor = currentAdmin(exchange).username();
    return workspaceAccessService.requireActiveWorkspace(workspaceId)
        .then(users.findByEmail(normalizeEmail(request.userEmail()))
            .switchIfEmpty(Mono.error(new GatewayException(404, "member_user_not_found", "User email not found"))))
        .flatMap(user -> memberships.findByWorkspaceIdAndUserId(workspaceId, user.id())
            .defaultIfEmpty(new WorkspaceMembership(
                null,
                workspaceId,
                user.id(),
                "MEMBER",
                "ACTIVE",
                Instant.now(),
                Instant.now()))
            .flatMap(existing -> memberships.save(new WorkspaceMembership(
                existing.id(),
                workspaceId,
                user.id(),
                workspaceAccessService.normalizeWorkspaceRole(request.role()),
                normalizeStatus(request.status()),
                existing.createdAt() == null ? Instant.now() : existing.createdAt(),
                Instant.now())))
            .flatMap(saved -> auditService.log(actor, "ADMIN_UPSERT_WORKSPACE_MEMBER", workspaceId.toString(), "user=" + saved.userId() + ", role=" + saved.role())
                .thenReturn(saved)));
  }

  @GetMapping("/{workspaceId}/model-configs")
  public Flux<WorkspaceModelConfig> modelConfigs(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId) {
    return workspaceAccessService.requireActiveWorkspace(workspaceId)
        .thenMany(modelConfigs.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE"));
  }

  @PostMapping("/{workspaceId}/model-configs")
  public Mono<WorkspaceModelConfig> upsertModelConfig(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId,
      @Valid @RequestBody UpsertModelConfigRequest request) {
    String actor = currentAdmin(exchange).username();
    return workspaceAccessService.requireActiveWorkspace(workspaceId)
        .then(modelConfigs.findByWorkspaceIdAndProviderAndModelPattern(
                workspaceId,
                normalizeProvider(request.provider()),
                request.modelPattern().trim())
            .defaultIfEmpty(new WorkspaceModelConfig(
                null,
                workspaceId,
                normalizeProvider(request.provider()),
                request.modelPattern().trim(),
                true,
                null,
                "ACTIVE",
                request.createdByUserId(),
                Instant.now(),
                Instant.now()))
            .flatMap(existing -> modelConfigs.save(new WorkspaceModelConfig(
                existing.id(),
                workspaceId,
                normalizeProvider(request.provider()),
                request.modelPattern().trim(),
                request.enabled(),
                request.maxTokens(),
                normalizeStatus(request.status()),
                request.createdByUserId() == null ? existing.createdByUserId() : request.createdByUserId(),
                existing.createdAt() == null ? Instant.now() : existing.createdAt(),
                Instant.now())))
            .flatMap(saved -> auditService.log(actor, "ADMIN_UPSERT_WORKSPACE_MODEL_CONFIG", workspaceId.toString(), saved.provider() + ":" + saved.modelPattern())
                .thenReturn(saved)));
  }

  @GetMapping("/{workspaceId}/billing")
  public Flux<Map<String, Object>> billing(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId,
      @RequestParam(required = false) String month) {
    YearMonth ym = resolveMonth(month);
    Instant start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    return workspaceAccessService.requireActiveWorkspace(workspaceId)
        .thenMany(databaseClient.sql("""
            SELECT
              ue.provider AS provider,
              ue.model AS model,
              count(*) AS requests,
              coalesce(sum(ue.prompt_tokens), 0) AS prompt_tokens,
              coalesce(sum(ue.completion_tokens), 0) AS completion_tokens,
              coalesce(sum(ue.total_tokens), 0) AS total_tokens,
              coalesce(sum(ue.estimated_cost_usd), 0) AS total_cost_usd
            FROM usage_events ue
            JOIN api_keys ak ON ak.id = ue.api_key_id
            WHERE ak.workspace_id = :workspace_id
              AND ue.created_at >= :start_time
              AND ue.created_at < :end_time
            GROUP BY ue.provider, ue.model
            ORDER BY requests DESC
            """)
            .bind("workspace_id", workspaceId)
            .bind("start_time", start)
            .bind("end_time", end)
            .fetch()
            .all());
  }

  private String normalizeEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new GatewayException(400, "invalid_email", "userEmail is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ACTIVE";
    }
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("ACTIVE") && !normalized.equals("DISABLED")) {
      throw new GatewayException(400, "invalid_status", "status must be ACTIVE or DISABLED");
    }
    return normalized;
  }

  private String normalizeProvider(String provider) {
    if (provider == null || provider.isBlank()) {
      throw new GatewayException(400, "invalid_provider", "provider is required");
    }
    String normalized = provider.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "OPENAI", "ANTHROPIC", "AZURE_OPENAI", "GEMINI" -> normalized;
      default -> throw new GatewayException(400, "invalid_provider", "provider must be OPENAI/ANTHROPIC/AZURE_OPENAI/GEMINI");
    };
  }

  private String normalizeRole(String role, String fallback) {
    if (role == null || role.isBlank()) {
      return fallback;
    }
    return workspaceAccessService.normalizeWorkspaceRole(role);
  }

  private YearMonth resolveMonth(String rawMonth) {
    if (rawMonth == null || rawMonth.isBlank()) {
      return YearMonth.now(ZoneOffset.UTC);
    }
    try {
      return YearMonth.parse(rawMonth.trim());
    } catch (DateTimeParseException ex) {
      throw new GatewayException(400, "invalid_month", "month must use YYYY-MM format");
    }
  }

  private AdminPrincipal currentAdmin(ServerWebExchange exchange) {
    AdminPrincipal principal = exchange.getAttribute(AdminSecurityWebFilter.ADMIN_PRINCIPAL_ATTR);
    if (principal == null) {
      throw new GatewayException(401, "admin_missing_context", "Admin auth context is missing");
    }
    return principal;
  }

  public record UpsertMemberRequest(
      @NotBlank String userEmail,
      @NotBlank String role,
      String status) {}

  public record CreateWorkspaceRequest(
      @NotBlank String name,
      @NotBlank String ownerUserId,
      String type,
      String ownerRole) {
    public UUID parsedOwnerUserId() {
      try {
        return UUID.fromString(ownerUserId);
      } catch (IllegalArgumentException ex) {
        throw new GatewayException(400, "invalid_owner_user_id", "ownerUserId must be a valid UUID");
      }
    }
  }

  public record UpsertModelConfigRequest(
      @NotBlank String provider,
      @NotBlank String modelPattern,
      boolean enabled,
      Integer maxTokens,
      String status,
      UUID createdByUserId) {}
}
