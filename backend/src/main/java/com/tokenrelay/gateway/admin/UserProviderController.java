package com.tokenrelay.gateway.admin;

import com.tokenrelay.gateway.domain.ProviderKey;
import com.tokenrelay.gateway.repository.ProviderKeyRepository;
import com.tokenrelay.gateway.service.AuditService;
import com.tokenrelay.gateway.service.AuthService;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.ProviderHealthCheckService;
import com.tokenrelay.gateway.service.ProviderKeySecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/me/provider-keys")
public class UserProviderController {
  private final AuthService authService;
  private final ProviderKeyRepository providerKeys;
  private final ProviderKeySecurityService providerKeySecurity;
  private final ProviderHealthCheckService providerHealthCheckService;
  private final AuditService auditService;

  public UserProviderController(
      AuthService authService,
      ProviderKeyRepository providerKeys,
      ProviderKeySecurityService providerKeySecurity,
      ProviderHealthCheckService providerHealthCheckService,
      AuditService auditService) {
    this.authService = authService;
    this.providerKeys = providerKeys;
    this.providerKeySecurity = providerKeySecurity;
    this.providerHealthCheckService = providerHealthCheckService;
    this.auditService = auditService;
  }

  @GetMapping
  public Flux<UserProviderKeyView> list(ServerWebExchange exchange) {
    return authService.authenticate(exchange)
        .flatMapMany(context -> providerKeys.findByOwnerUserIdOrderByPriorityAsc(context.user().id())
            .map(this::toView));
  }

  @PostMapping
  public Mono<UserProviderKeyView> create(
      ServerWebExchange exchange,
      @Valid @RequestBody CreateUserProviderKeyRequest request) {
    return authService.authenticate(exchange)
        .flatMap(context -> {
          ProviderKey providerKey = new ProviderKey(
              null,
              context.user().id(),
              normalizeProvider(request.provider()),
              request.name(),
              request.baseUrl(),
              request.apiKey(),
              blankToNull(request.azureDeployment()),
              normalizeProviderStatus(request.status()),
              request.priority(),
              "UNKNOWN",
              null,
              null,
              null,
              null);
          return providerKeys.save(providerKeySecurity.encryptForStorage(providerKey))
              .flatMap((ProviderKey saved) -> auditService.log(context.user().email(), "USER_CREATE_PROVIDER_KEY", saved.id().toString(), saved.provider())
                  .thenReturn(saved))
              .map(saved -> toView((ProviderKey) saved));
        });
  }

  @PostMapping("/{providerKeyId}")
  public Mono<UserProviderKeyView> update(
      ServerWebExchange exchange,
      @PathVariable UUID providerKeyId,
      @Valid @RequestBody UpdateUserProviderKeyRequest request) {
    return authService.authenticate(exchange)
        .flatMap(context -> providerKeys.findByIdAndOwnerUserId(providerKeyId, context.user().id())
            .switchIfEmpty(Mono.error(new GatewayException(404, "provider_key_not_found", "Provider key not found")))
            .flatMap(existing -> {
              ProviderKey updated = new ProviderKey(
                  existing.id(),
                  existing.ownerUserId(),
                  request.provider() == null || request.provider().isBlank()
                      ? existing.provider()
                      : normalizeProvider(request.provider()),
                  request.name() == null || request.name().isBlank() ? existing.name() : request.name().trim(),
                  request.baseUrl() == null || request.baseUrl().isBlank() ? existing.baseUrl() : request.baseUrl().trim(),
                  request.apiKey() == null || request.apiKey().isBlank() ? existing.apiKey() : request.apiKey().trim(),
                  request.azureDeployment() == null ? existing.azureDeployment() : blankToNull(request.azureDeployment()),
                  request.status() == null || request.status().isBlank() ? existing.status() : normalizeProviderStatus(request.status()),
                  request.priority() == null ? existing.priority() : request.priority(),
                  existing.healthStatus(),
                  existing.lastCheckedAt(),
                  existing.lastError(),
                  existing.createdAt(),
                  Instant.now());
              ProviderKey toSave = request.apiKey() == null || request.apiKey().isBlank()
                  ? updated
                  : providerKeySecurity.encryptForStorage(updated);
              return providerKeys.save(toSave)
                  .flatMap((ProviderKey saved) -> auditService.log(context.user().email(), "USER_UPDATE_PROVIDER_KEY", saved.id().toString(), saved.provider())
                      .thenReturn(saved))
                  .map(saved -> toView((ProviderKey) saved));
            }));
  }

  @PostMapping("/{providerKeyId}/check")
  public Mono<UserProviderHealthCheckView> check(
      ServerWebExchange exchange,
      @PathVariable UUID providerKeyId) {
    return authService.authenticate(exchange)
        .flatMap(context -> providerKeys.findByIdAndOwnerUserId(providerKeyId, context.user().id())
            .switchIfEmpty(Mono.error(new GatewayException(404, "provider_key_not_found", "Provider key not found")))
            .flatMap(stored -> providerHealthCheckService.check(providerKeySecurity.decryptForRuntime(stored))
                .flatMap(result -> providerKeys.save(new ProviderKey(
                        stored.id(),
                        stored.ownerUserId(),
                        stored.provider(),
                        stored.name(),
                        stored.baseUrl(),
                        stored.apiKey(),
                        stored.azureDeployment(),
                        stored.status(),
                        stored.priority(),
                        result.healthStatus(),
                        result.checkedAt(),
                        result.message(),
                        stored.createdAt(),
                        Instant.now()))
                    .flatMap(saved -> auditService.log(context.user().email(), "USER_CHECK_PROVIDER_KEY", saved.id().toString(), result.healthStatus())
                        .thenReturn(new UserProviderHealthCheckView(
                            saved.id(),
                            saved.provider(),
                            saved.name(),
                            result.healthStatus(),
                            result.statusCode(),
                            result.message(),
                            result.latencyMs(),
                            result.checkedAt()))))));
  }

  private UserProviderKeyView toView(ProviderKey key) {
    return new UserProviderKeyView(
        key.id(),
        key.provider(),
        key.name(),
        key.baseUrl(),
        providerKeySecurity.maskStored(key.apiKey()),
        key.azureDeployment(),
        key.status(),
        key.priority(),
        key.healthStatus(),
        key.lastCheckedAt(),
        key.lastError(),
        key.createdAt(),
        key.updatedAt());
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

  private String normalizeProviderStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ACTIVE";
    }
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "ACTIVE", "DISABLED" -> normalized;
      default -> throw new GatewayException(400, "invalid_provider_status", "status must be ACTIVE or DISABLED");
    };
  }

  private String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public record CreateUserProviderKeyRequest(
      @NotBlank String provider,
      @NotBlank String name,
      @NotBlank String baseUrl,
      @NotBlank String apiKey,
      String azureDeployment,
      int priority,
      String status) {}

  public record UpdateUserProviderKeyRequest(
      String provider,
      String name,
      String baseUrl,
      String apiKey,
      String azureDeployment,
      Integer priority,
      String status) {}

  public record UserProviderKeyView(
      UUID id,
      String provider,
      String name,
      String baseUrl,
      String apiKeyMasked,
      String azureDeployment,
      String status,
      int priority,
      String healthStatus,
      Instant lastCheckedAt,
      String lastError,
      Instant createdAt,
      Instant updatedAt) {}

  public record UserProviderHealthCheckView(
      UUID id,
      String provider,
      String name,
      String healthStatus,
      int httpStatus,
      String message,
      long latencyMs,
      Instant checkedAt) {}
}
