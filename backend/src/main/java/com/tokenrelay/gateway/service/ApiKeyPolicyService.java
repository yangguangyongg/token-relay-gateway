package com.tokenrelay.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tokenrelay.gateway.domain.ApiKeyModelScope;
import com.tokenrelay.gateway.domain.ApiKeyRecord;
import com.tokenrelay.gateway.repository.ApiKeyModelScopeRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ApiKeyPolicyService {
  private final ApiKeyModelScopeRepository modelScopes;

  public ApiKeyPolicyService(ApiKeyModelScopeRepository modelScopes) {
    this.modelScopes = modelScopes;
  }

  public Mono<Void> validate(ApiKeyRecord apiKey, JsonNode request) {
    if (apiKey == null || apiKey.id() == null) {
      return Mono.empty();
    }
    String model = request.path("model").asText("");
    if (model.isBlank()) {
      return Mono.error(new GatewayException(400, "model_required", "model is required"));
    }
    return modelScopes.findByApiKeyIdAndStatus(apiKey.id(), "ACTIVE")
        .collectList()
        .flatMap(scopes -> {
          if (scopes.isEmpty()) {
            return Mono.empty();
          }
          boolean allowed = scopes.stream()
              .map(scope -> toCandidate(scope, model))
              .filter(Optional::isPresent)
              .map(Optional::get)
              .max(Comparator.comparingInt(PolicyCandidate::score))
              .isPresent();
          if (!allowed) {
            return Mono.error(new GatewayException(403, "api_key_model_not_allowed", "Model is not allowed for this API key"));
          }
          return Mono.empty();
        });
  }

  public Mono<Void> replaceScopes(UUID apiKeyId, List<String> rawScopes) {
    List<String> scopes = normalizeScopes(rawScopes);
    return modelScopes.deleteByApiKeyId(apiKeyId)
        .thenMany(Flux.fromIterable(scopes)
            .flatMap(scope -> modelScopes.save(new ApiKeyModelScope(
                null,
                apiKeyId,
                scope,
                "ACTIVE",
                Instant.now(),
                Instant.now()))))
        .then();
  }

  public List<String> normalizeScopes(List<String> rawScopes) {
    if (rawScopes == null || rawScopes.isEmpty()) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String rawScope : rawScopes) {
      if (rawScope == null || rawScope.isBlank()) {
        continue;
      }
      String[] segments = rawScope.split("[,\\n\\r]+");
      for (String segment : segments) {
        String scope = normalize(segment);
        if (!scope.isBlank()) {
          normalized.add(scope);
        }
      }
    }
    return new ArrayList<>(normalized);
  }

  private Optional<PolicyCandidate> toCandidate(ApiKeyModelScope scope, String model) {
    String normalizedModel = normalize(model);
    String pattern = normalize(scope.modelPattern());
    if (pattern.equals("*")) {
      return Optional.of(new PolicyCandidate(scope, 1));
    }
    if (pattern.endsWith("*")) {
      String prefix = pattern.substring(0, pattern.length() - 1);
      if (normalizedModel.startsWith(prefix)) {
        return Optional.of(new PolicyCandidate(scope, 100 + prefix.length()));
      }
      return Optional.empty();
    }
    if (normalizedModel.equals(pattern)) {
      return Optional.of(new PolicyCandidate(scope, 1000 + pattern.length()));
    }
    return Optional.empty();
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private record PolicyCandidate(ApiKeyModelScope scope, int score) {}
}
