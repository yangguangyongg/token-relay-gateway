package com.tokenrelay.gateway.admin;

import com.tokenrelay.gateway.config.AdminSecurityWebFilter;
import com.tokenrelay.gateway.domain.ApiKeyRecord;
import com.tokenrelay.gateway.domain.AuditLog;
import com.tokenrelay.gateway.domain.GatewayUser;
import com.tokenrelay.gateway.domain.ProviderKey;
import com.tokenrelay.gateway.repository.ApiKeyRepository;
import com.tokenrelay.gateway.repository.AuditLogRepository;
import com.tokenrelay.gateway.repository.GatewayUserRepository;
import com.tokenrelay.gateway.repository.ProviderKeyRepository;
import com.tokenrelay.gateway.service.AuditService;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.HashService;
import com.tokenrelay.gateway.service.ProviderKeySecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class AdminController {
  private final GatewayUserRepository users;
  private final ApiKeyRepository apiKeys;
  private final ProviderKeyRepository providerKeys;
  private final AuditLogRepository auditLogs;
  private final HashService hashService;
  private final ProviderKeySecurityService providerKeySecurity;
  private final AuditService auditService;
  private final DatabaseClient databaseClient;
  private final SecureRandom secureRandom = new SecureRandom();

  public AdminController(
      GatewayUserRepository users,
      ApiKeyRepository apiKeys,
      ProviderKeyRepository providerKeys,
      AuditLogRepository auditLogs,
      HashService hashService,
      ProviderKeySecurityService providerKeySecurity,
      AuditService auditService,
      R2dbcEntityTemplate template) {
    this.users = users;
    this.apiKeys = apiKeys;
    this.providerKeys = providerKeys;
    this.auditLogs = auditLogs;
    this.hashService = hashService;
    this.providerKeySecurity = providerKeySecurity;
    this.auditService = auditService;
    this.databaseClient = template.getDatabaseClient();
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }

  @GetMapping("/admin/users")
  public Flux<GatewayUser> users(ServerWebExchange exchange) {
    return users.findAll();
  }

  @PostMapping("/admin/users")
  public Mono<GatewayUser> createUser(ServerWebExchange exchange, @Valid @RequestBody CreateUserRequest request) {
    String actor = currentAdmin(exchange).username();
    GatewayUser user = new GatewayUser(null, request.email(), request.displayName(), "ACTIVE", request.monthlyTokenQuota(), null);
    return users.save(user)
        .flatMap(saved -> auditService.log(actor, "CREATE_USER", saved.id().toString(), saved.email()).thenReturn(saved));
  }

  @GetMapping("/admin/api-keys")
  public Flux<ApiKeyRecord> apiKeys(ServerWebExchange exchange) {
    return apiKeys.findAll();
  }

  @PostMapping("/admin/api-keys")
  public Mono<ResponseEntity<CreateApiKeyResponse>> createApiKey(
      ServerWebExchange exchange,
      @Valid @RequestBody CreateApiKeyRequest request) {
    String actor = currentAdmin(exchange).username();
    String rawKey = "tg_" + randomHex(32);
    ApiKeyRecord key = new ApiKeyRecord(
        null,
        request.userId(),
        request.name(),
        rawKey.substring(0, 10),
        hashService.sha256(rawKey),
        "ACTIVE",
        request.rateLimitPerMinute(),
        null,
        null);
    return apiKeys.save(key)
        .flatMap(saved -> auditService.log(actor, "CREATE_API_KEY", saved.id().toString(), saved.keyPrefix()).thenReturn(saved))
        .map(saved -> ResponseEntity.ok(new CreateApiKeyResponse(saved, rawKey)));
  }

  @GetMapping("/admin/provider-keys")
  public Flux<ProviderKeyView> providerKeys(ServerWebExchange exchange) {
    return providerKeys.findAll().map(this::toProviderKeyView);
  }

  @PostMapping("/admin/provider-keys")
  public Mono<ProviderKeyView> createProviderKey(
      ServerWebExchange exchange,
      @Valid @RequestBody CreateProviderKeyRequest request) {
    String actor = currentAdmin(exchange).username();
    ProviderKey providerKey = new ProviderKey(
        null,
        request.provider(),
        request.name(),
        request.baseUrl(),
        request.apiKey(),
        request.azureDeployment(),
        "ACTIVE",
        request.priority(),
        null);
    return providerKeys.save(providerKeySecurity.encryptForStorage(providerKey))
        .flatMap(saved -> auditService.log(actor, "CREATE_PROVIDER_KEY", saved.id().toString(), saved.provider()).thenReturn(saved))
        .map(this::toProviderKeyView);
  }

  @GetMapping("/admin/audit-logs")
  public Flux<AuditLog> auditLogs(ServerWebExchange exchange) {
    return auditLogs.findAll();
  }

  @GetMapping("/admin/usage-summary")
  public Flux<Map<String, Object>> usageSummary(ServerWebExchange exchange) {
    return databaseClient.sql("""
        SELECT provider, model, count(*) AS requests, coalesce(sum(total_tokens), 0) AS total_tokens
        FROM usage_events
        GROUP BY provider, model
        ORDER BY requests DESC
        """)
        .fetch()
        .all();
  }

  private AdminPrincipal currentAdmin(ServerWebExchange exchange) {
    AdminPrincipal principal = exchange.getAttribute(AdminSecurityWebFilter.ADMIN_PRINCIPAL_ATTR);
    if (principal == null) {
      throw new GatewayException(401, "admin_missing_context", "Admin auth context is missing");
    }
    return principal;
  }

  private String randomHex(int bytes) {
    byte[] buffer = new byte[bytes];
    secureRandom.nextBytes(buffer);
    return HexFormat.of().formatHex(buffer);
  }

  public record CreateUserRequest(@Email String email, @NotBlank String displayName, long monthlyTokenQuota) {}

  public record CreateApiKeyRequest(UUID userId, @NotBlank String name, int rateLimitPerMinute) {}

  public record CreateApiKeyResponse(ApiKeyRecord apiKey, String rawKey) {}

  public record CreateProviderKeyRequest(
      @NotBlank String provider,
      @NotBlank String name,
      @NotBlank String baseUrl,
      @NotBlank String apiKey,
      String azureDeployment,
      int priority) {}

  public record ProviderKeyView(
      UUID id,
      String provider,
      String name,
      String baseUrl,
      String apiKeyMasked,
      String azureDeployment,
      String status,
      int priority,
      Instant createdAt) {}

  private ProviderKeyView toProviderKeyView(ProviderKey key) {
    return new ProviderKeyView(
        key.id(),
        key.provider(),
        key.name(),
        key.baseUrl(),
        providerKeySecurity.maskStored(key.apiKey()),
        key.azureDeployment(),
        key.status(),
        key.priority(),
        key.createdAt());
  }
}
