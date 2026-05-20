package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.Workspace;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface WorkspaceRepository extends ReactiveCrudRepository<Workspace, UUID> {
  Mono<Workspace> findBySlug(String slug);
}
