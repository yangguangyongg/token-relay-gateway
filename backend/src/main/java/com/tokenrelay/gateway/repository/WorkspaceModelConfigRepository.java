package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.WorkspaceModelConfig;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface WorkspaceModelConfigRepository extends ReactiveCrudRepository<WorkspaceModelConfig, UUID> {
  Flux<WorkspaceModelConfig> findByWorkspaceIdAndStatus(UUID workspaceId, String status);
  Mono<WorkspaceModelConfig> findByWorkspaceIdAndProviderAndModelPattern(UUID workspaceId, String provider, String modelPattern);
}
