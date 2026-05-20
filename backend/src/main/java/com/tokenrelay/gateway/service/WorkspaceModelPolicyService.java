package com.tokenrelay.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tokenrelay.gateway.domain.WorkspaceModelConfig;
import com.tokenrelay.gateway.repository.WorkspaceModelConfigRepository;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class WorkspaceModelPolicyService {
  private final WorkspaceModelConfigRepository workspaceModelConfigs;

  public WorkspaceModelPolicyService(WorkspaceModelConfigRepository workspaceModelConfigs) {
    this.workspaceModelConfigs = workspaceModelConfigs;
  }

  public Mono<Void> validate(UUID workspaceId, JsonNode request) {
    if (workspaceId == null) {
      return Mono.empty();
    }
    String model = request.path("model").asText("");
    if (model.isBlank()) {
      return Mono.error(new GatewayException(400, "model_required", "model is required"));
    }
    int requestedMaxTokens = request.path("max_tokens").asInt(-1);
    return workspaceModelConfigs.findByWorkspaceIdAndStatus(workspaceId, "ACTIVE")
        .map(config -> toCandidate(config, model))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .sort(Comparator.comparingInt(PolicyCandidate::score).reversed())
        .next()
        .flatMap(candidate -> {
          WorkspaceModelConfig config = candidate.config();
          if (!config.enabled()) {
            return Mono.error(new GatewayException(403, "model_not_allowed", "Model is disabled by workspace policy"));
          }
          if (config.maxTokens() != null && config.maxTokens() > 0 && requestedMaxTokens > config.maxTokens()) {
            return Mono.error(new GatewayException(
                400,
                "workspace_model_max_tokens_exceeded",
                "Requested max_tokens exceeds workspace model limit"));
          }
          return Mono.<Void>empty();
        })
        .switchIfEmpty(Mono.<Void>empty());
  }

  private Optional<PolicyCandidate> toCandidate(WorkspaceModelConfig config, String model) {
    String normalizedModel = normalize(model);
    String pattern = normalize(config.modelPattern());
    if (pattern.equals("*")) {
      return Optional.of(new PolicyCandidate(config, 1));
    }
    if (pattern.endsWith("*")) {
      String prefix = pattern.substring(0, pattern.length() - 1);
      if (normalizedModel.startsWith(prefix)) {
        return Optional.of(new PolicyCandidate(config, 100 + prefix.length()));
      }
      return Optional.empty();
    }
    if (normalizedModel.equals(pattern)) {
      return Optional.of(new PolicyCandidate(config, 1000 + pattern.length()));
    }
    return Optional.empty();
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private record PolicyCandidate(WorkspaceModelConfig config, int score) {}
}
