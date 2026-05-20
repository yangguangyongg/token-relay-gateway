package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.WorkspaceMembership;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface WorkspaceMembershipRepository extends ReactiveCrudRepository<WorkspaceMembership, UUID> {
  Flux<WorkspaceMembership> findByUserIdAndStatus(UUID userId, String status);
  Flux<WorkspaceMembership> findByWorkspaceIdAndStatus(UUID workspaceId, String status);
  Mono<WorkspaceMembership> findByWorkspaceIdAndUserIdAndStatus(UUID workspaceId, UUID userId, String status);
  Mono<WorkspaceMembership> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
}
