package com.tokenrelay.gateway.admin;

import com.tokenrelay.gateway.config.GatewayProperties;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class AdminController {
  private final GatewayProperties properties;
  private final GatewayUserRepository users;
  private final ApiKeyRepository apiKeys;
  private final ProviderKeyRepository providerKeys;
  private final AuditLogRepository auditLogs;
  private final HashService hashService;
  private final AuditService auditService;
  private final DatabaseClient databaseClient;
  private final SecureRandom secureRandom = new SecureRandom();

  public AdminController(
      GatewayProperties properties,
      GatewayUserRepository users,
      ApiKeyRepository apiKeys,
      ProviderKeyRepository providerKeys,
      AuditLogRepository auditLogs,
      HashService hashService,
      AuditService auditService,
      R2dbcEntityTemplate template) {
    this.properties = properties;
    this.users = users;
    this.apiKeys = apiKeys;
    this.providerKeys = providerKeys;
    this.auditLogs = auditLogs;
    this.hashService = hashService;
    this.auditService = auditService;
    this.databaseClient = template.getDatabaseClient();
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }

  @GetMapping("/admin/users")
  public Flux<GatewayUser> users(@RequestHeader("X-Admin-Key") String adminKey) {
    requireAdmin(adminKey);
    return users.findAll();
  }

  @PostMapping("/admin/users")
  public Mono<GatewayUser> createUser(@RequestHeader("X-Admin-Key") String adminKey, @Valid @RequestBody CreateUserRequest request) {
    requireAdmin(adminKey);
    GatewayUser user = new GatewayUser(null, request.email(), request.displayName(), "ACTIVE", request.monthlyTokenQuota(), null);
    return users.save(user)
        .flatMap(saved -> auditService.log("admin", "CREATE_USER", saved.id().toString(), saved.email()).thenReturn(saved));
  }

  @GetMapping("/admin/api-keys")
  public Flux<ApiKeyRecord> apiKeys(@RequestHeader("X-Admin-Key") String adminKey) {
    requireAdmin(adminKey);
    return apiKeys.findAll();
  }

  @PostMapping("/admin/api-keys")
  public Mono<ResponseEntity<CreateApiKeyResponse>> createApiKey(
      @RequestHeader("X-Admin-Key") String adminKey,
      @Valid @RequestBody CreateApiKeyRequest request) {
    requireAdmin(adminKey);
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
        .flatMap(saved -> auditService.log("admin", "CREATE_API_KEY", saved.id().toString(), saved.keyPrefix()).thenReturn(saved))
        .map(saved -> ResponseEntity.ok(new CreateApiKeyResponse(saved, rawKey)));
  }

  @GetMapping("/admin/provider-keys")
  public Flux<ProviderKey> providerKeys(@RequestHeader("X-Admin-Key") String adminKey) {
    requireAdmin(adminKey);
    return providerKeys.findAll();
  }

  @PostMapping("/admin/provider-keys")
  public Mono<ProviderKey> createProviderKey(
      @RequestHeader("X-Admin-Key") String adminKey,
      @Valid @RequestBody CreateProviderKeyRequest request) {
    requireAdmin(adminKey);
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
    return providerKeys.save(providerKey)
        .flatMap(saved -> auditService.log("admin", "CREATE_PROVIDER_KEY", saved.id().toString(), saved.provider()).thenReturn(saved));
  }

  @GetMapping("/admin/audit-logs")
  public Flux<AuditLog> auditLogs(@RequestHeader("X-Admin-Key") String adminKey) {
    requireAdmin(adminKey);
    return auditLogs.findAll();
  }

  @GetMapping("/admin/usage-summary")
  public Flux<Map<String, Object>> usageSummary(@RequestHeader("X-Admin-Key") String adminKey) {
    requireAdmin(adminKey);
    return databaseClient.sql("""
        SELECT provider, model, count(*) AS requests, coalesce(sum(total_tokens), 0) AS total_tokens
        FROM usage_events
        GROUP BY provider, model
        ORDER BY requests DESC
        """)
        .fetch()
        .all();
  }

  private void requireAdmin(String adminKey) {
    if (adminKey == null || !adminKey.equals(properties.adminApiKey())) {
      throw new GatewayException(401, "admin_unauthorized", "Invalid admin key");
    }
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
}
