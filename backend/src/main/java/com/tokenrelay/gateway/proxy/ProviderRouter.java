package com.tokenrelay.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.tokenrelay.gateway.adapter.ProviderAdapter;
import com.tokenrelay.gateway.auth.AuthContext;
import com.tokenrelay.gateway.domain.ProviderKey;
import com.tokenrelay.gateway.repository.ProviderKeyRepository;
import com.tokenrelay.gateway.service.ProviderKeySecurityService;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ProviderRouter {
  private final ProviderKeyRepository providerKeys;
  private final List<ProviderAdapter> adapters;
  private final ProviderKeySecurityService providerKeySecurity;

  public ProviderRouter(
      ProviderKeyRepository providerKeys,
      List<ProviderAdapter> adapters,
      ProviderKeySecurityService providerKeySecurity) {
    this.providerKeys = providerKeys;
    this.adapters = adapters;
    this.providerKeySecurity = providerKeySecurity;
  }

  public Mono<List<RouteCandidate>> candidates(AuthContext context, JsonNode request) {
    return providerKeys.findByOwnerUserIdAndStatusOrderByPriorityAsc(context.user().id(), "ACTIVE")
        .collectList()
        .flatMap(userOwnedKeys -> {
          boolean hasUserOwned = !userOwnedKeys.isEmpty();
          Flux<ProviderKey> pool = hasUserOwned
              ? Flux.fromIterable(userOwnedKeys)
              : providerKeys.findByOwnerUserIdIsNullAndStatusOrderByPriorityAsc("ACTIVE");
          return pool
              .filter(this::isRoutableByHealth)
              .map(providerKeySecurity::decryptForRuntime)
              .flatMap(key -> adapterFor(key, request).map(adapter -> new RouteCandidate(key, adapter, hasUserOwned)))
              .sort(Comparator.comparingInt(candidate -> routeScore(candidate.providerKey(), request)))
              .collectList();
        });
  }

  private Mono<ProviderAdapter> adapterFor(ProviderKey key, JsonNode request) {
    return adapters.stream()
        .filter(adapter -> adapter.supports(key, request))
        .findFirst()
        .map(Mono::just)
        .orElseGet(Mono::empty);
  }

  private int routeScore(ProviderKey key, JsonNode request) {
    String model = request.path("model").asText("");
    int modelScore = 50;
    if (model.startsWith("claude-") && "ANTHROPIC".equalsIgnoreCase(key.provider())) modelScore = 0;
    if (model.startsWith("gpt-") && "OPENAI".equalsIgnoreCase(key.provider())) modelScore = 0;
    if (model.startsWith("gemini-") && "GEMINI".equalsIgnoreCase(key.provider())) modelScore = 0;
    return modelScore + key.priority();
  }

  private boolean isRoutableByHealth(ProviderKey key) {
    String health = key.healthStatus();
    if (health == null || health.isBlank()) {
      return true;
    }
    return !"UNHEALTHY".equalsIgnoreCase(health);
  }

  public record RouteCandidate(ProviderKey providerKey, ProviderAdapter adapter, boolean byokScope) {}
}
