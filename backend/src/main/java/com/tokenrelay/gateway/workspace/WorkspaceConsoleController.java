package com.tokenrelay.gateway.workspace;

import com.tokenrelay.gateway.domain.ApiKeyRecord;
import com.tokenrelay.gateway.domain.GatewayUser;
import com.tokenrelay.gateway.domain.Workspace;
import com.tokenrelay.gateway.domain.WorkspaceMembership;
import com.tokenrelay.gateway.domain.WorkspaceModelConfig;
import com.tokenrelay.gateway.repository.ApiKeyRepository;
import com.tokenrelay.gateway.repository.GatewayUserRepository;
import com.tokenrelay.gateway.repository.WorkspaceMembershipRepository;
import com.tokenrelay.gateway.repository.WorkspaceModelConfigRepository;
import com.tokenrelay.gateway.repository.WorkspaceRepository;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.HashService;
import com.tokenrelay.gateway.service.WorkspaceAccessService;
import com.tokenrelay.gateway.service.WorkspaceSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/workspace")
public class WorkspaceConsoleController {
  private final WorkspaceSessionService workspaceSessionService;
  private final WorkspaceAccessService workspaceAccessService;
  private final WorkspaceRepository workspaces;
  private final WorkspaceMembershipRepository memberships;
  private final GatewayUserRepository users;
  private final ApiKeyRepository apiKeys;
  private final WorkspaceModelConfigRepository modelConfigs;
  private final HashService hashService;
  private final DatabaseClient databaseClient;
  private final SecureRandom secureRandom = new SecureRandom();

  public WorkspaceConsoleController(
      WorkspaceSessionService workspaceSessionService,
      WorkspaceAccessService workspaceAccessService,
      WorkspaceRepository workspaces,
      WorkspaceMembershipRepository memberships,
      GatewayUserRepository users,
      ApiKeyRepository apiKeys,
      WorkspaceModelConfigRepository modelConfigs,
      HashService hashService,
      DatabaseClient databaseClient) {
    this.workspaceSessionService = workspaceSessionService;
    this.workspaceAccessService = workspaceAccessService;
    this.workspaces = workspaces;
    this.memberships = memberships;
    this.users = users;
    this.apiKeys = apiKeys;
    this.modelConfigs = modelConfigs;
    this.hashService = hashService;
    this.databaseClient = databaseClient;
  }

  @GetMapping("/workspaces")
  public Flux<WorkspaceOverview> myWorkspaces(ServerWebExchange exchange) {
    return workspaceSessionService.authenticate(exchange)
        .flatMapMany(user -> memberships.findByUserIdAndStatus(user.id(), "ACTIVE")
            .flatMap(membership -> workspaces.findById(membership.workspaceId())
                .filter(workspace -> "ACTIVE".equalsIgnoreCase(workspace.status()))
                .map(workspace -> new WorkspaceOverview(
                    workspace.id(),
                    workspace.name(),
                    workspace.slug(),
                    workspace.type(),
                    workspace.status(),
                    membership.role()))));
  }

  @PostMapping("/workspaces")
  public Mono<WorkspaceOverview> createWorkspace(
      ServerWebExchange exchange,
      @Valid @RequestBody CreateWorkspaceRequest request) {
    return workspaceSessionService.authenticate(exchange)
        .flatMap(user -> {
          Workspace workspace = workspaceAccessService.newWorkspace(
              request.name(),
              user.id(),
              request.type());
          return workspaces.save(workspace)
              .flatMap(savedWorkspace -> memberships.save(
                  workspaceAccessService.newMembership(savedWorkspace.id(), user.id(), WorkspaceRole.OWNER))
                  .thenReturn(new WorkspaceOverview(
                      savedWorkspace.id(),
                      savedWorkspace.name(),
                      savedWorkspace.slug(),
                      savedWorkspace.type(),
                      savedWorkspace.status(),
                      WorkspaceRole.OWNER.name())));
        });
  }

  @GetMapping("/workspaces/{workspaceId}/members")
  public Flux<MemberView> workspaceMembers(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId) {
    return managerUser(exchange, workspaceId)
        .flatMapMany(user -> memberships.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE")
            .flatMap(member -> users.findById(member.userId())
                .map(memberUser -> new MemberView(
                    member.id(),
                    member.workspaceId(),
                    member.userId(),
                    memberUser.email(),
                    memberUser.displayName(),
                    member.role(),
                    member.status(),
                    member.createdAt()))));
  }

  @PostMapping("/workspaces/{workspaceId}/members")
  public Mono<MemberView> upsertMember(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId,
      @Valid @RequestBody UpsertMemberRequest request) {
    return managerUser(exchange, workspaceId)
        .flatMap(actor -> users.findByEmail(normalizeEmail(request.userEmail()))
            .switchIfEmpty(Mono.error(new GatewayException(404, "member_user_not_found", "User email not found")))
            .flatMap(memberUser -> memberships.findByWorkspaceIdAndUserId(workspaceId, memberUser.id())
                .defaultIfEmpty(new WorkspaceMembership(
                    null,
                    workspaceId,
                    memberUser.id(),
                    "MEMBER",
                    "ACTIVE",
                    Instant.now(),
                    Instant.now()))
                .flatMap(existing -> {
                  WorkspaceMembership updated = new WorkspaceMembership(
                      existing.id(),
                      workspaceId,
                      memberUser.id(),
                      workspaceAccessService.normalizeWorkspaceRole(request.role()),
                      normalizeMembershipStatus(request.status()),
                      existing.createdAt() == null ? Instant.now() : existing.createdAt(),
                      Instant.now());
                  return memberships.save(updated)
                      .map(saved -> new MemberView(
                          saved.id(),
                          saved.workspaceId(),
                          saved.userId(),
                          memberUser.email(),
                          memberUser.displayName(),
                          saved.role(),
                          saved.status(),
                          saved.createdAt()));
                })));
  }

  @GetMapping("/workspaces/{workspaceId}/api-keys")
  public Flux<ApiKeyRecord> workspaceApiKeys(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId) {
    return managerUser(exchange, workspaceId)
        .flatMapMany(user -> apiKeys.findByWorkspaceId(workspaceId));
  }

  @PostMapping("/workspaces/{workspaceId}/api-keys")
  public Mono<ResponseEntity<CreateApiKeyResponse>> createWorkspaceApiKey(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId,
      @Valid @RequestBody CreateApiKeyRequest request) {
    return managerUser(exchange, workspaceId)
        .flatMap(actor -> {
          String rawKey = "tg_" + randomHex(32);
          ApiKeyRecord key = new ApiKeyRecord(
              null,
              actor.id(),
              workspaceId,
              request.name(),
              rawKey.substring(0, 10),
              hashService.sha256(rawKey),
              "ACTIVE",
              request.rateLimitPerMinute() <= 0 ? 60 : request.rateLimitPerMinute(),
              request.monthlyTokenQuota() == null || request.monthlyTokenQuota() <= 0 ? null : request.monthlyTokenQuota(),
              null,
              null);
          return apiKeys.save(key)
              .map(saved -> ResponseEntity.ok(new CreateApiKeyResponse(saved, rawKey)));
        });
  }

  @GetMapping("/workspaces/{workspaceId}/billing")
  public Flux<Map<String, Object>> workspaceBilling(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId,
      @RequestParam(required = false) String month) {
    YearMonth ym = resolveMonth(month);
    Instant start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    return managerUser(exchange, workspaceId)
        .thenMany(databaseClient.sql("""
            SELECT
              ak.workspace_id::text AS workspace_id,
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
            GROUP BY ak.workspace_id, ue.provider, ue.model
            ORDER BY requests DESC
            """)
            .bind("workspace_id", workspaceId)
            .bind("start_time", start)
            .bind("end_time", end)
            .fetch()
            .all());
  }

  @GetMapping("/workspaces/{workspaceId}/model-configs")
  public Flux<WorkspaceModelConfig> modelConfigs(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId) {
    return managerUser(exchange, workspaceId)
        .flatMapMany(user -> modelConfigs.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE"));
  }

  @PostMapping("/workspaces/{workspaceId}/model-configs")
  public Mono<WorkspaceModelConfig> upsertModelConfig(
      ServerWebExchange exchange,
      @PathVariable UUID workspaceId,
      @Valid @RequestBody UpsertModelConfigRequest request) {
    return managerUser(exchange, workspaceId)
        .flatMap(user -> modelConfigs.findByWorkspaceIdAndProviderAndModelPattern(
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
                user.id(),
                Instant.now(),
                Instant.now()))
            .flatMap(existing -> modelConfigs.save(new WorkspaceModelConfig(
                existing.id(),
                workspaceId,
                normalizeProvider(request.provider()),
                request.modelPattern().trim(),
                request.enabled(),
                request.maxTokens(),
                normalizeModelConfigStatus(request.status()),
                existing.createdByUserId() == null ? user.id() : existing.createdByUserId(),
                existing.createdAt() == null ? Instant.now() : existing.createdAt(),
                Instant.now()))));
  }

  private Mono<GatewayUser> managerUser(ServerWebExchange exchange, UUID workspaceId) {
    return workspaceSessionService.authenticate(exchange)
        .flatMap(user -> workspaceAccessService.requireActiveWorkspace(workspaceId)
            .then(workspaceAccessService.requireManager(workspaceId, user.id()))
            .thenReturn(user));
  }

  private String normalizeMembershipStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ACTIVE";
    }
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("ACTIVE") && !normalized.equals("DISABLED")) {
      throw new GatewayException(400, "invalid_membership_status", "membership status must be ACTIVE or DISABLED");
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

  private String normalizeModelConfigStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ACTIVE";
    }
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("ACTIVE") && !normalized.equals("DISABLED")) {
      throw new GatewayException(400, "invalid_model_config_status", "status must be ACTIVE or DISABLED");
    }
    return normalized;
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

  private String normalizeEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new GatewayException(400, "invalid_email", "userEmail is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String randomHex(int bytes) {
    byte[] buffer = new byte[bytes];
    secureRandom.nextBytes(buffer);
    return HexFormat.of().formatHex(buffer);
  }

  public record WorkspaceOverview(
      UUID workspaceId,
      String name,
      String slug,
      String type,
      String status,
      String role) {}

  public record CreateWorkspaceRequest(@NotBlank String name, String type) {}

  public record MemberView(
      UUID membershipId,
      UUID workspaceId,
      UUID userId,
      String email,
      String displayName,
      String role,
      String status,
      Instant createdAt) {}

  public record UpsertMemberRequest(
      @NotBlank String userEmail,
      @NotBlank String role,
      String status) {}

  public record CreateApiKeyRequest(
      @NotBlank String name,
      int rateLimitPerMinute,
      Long monthlyTokenQuota) {}

  public record CreateApiKeyResponse(ApiKeyRecord apiKey, String rawKey) {}

  public record UpsertModelConfigRequest(
      @NotBlank String provider,
      @NotBlank String modelPattern,
      boolean enabled,
      Integer maxTokens,
      String status) {}
}
