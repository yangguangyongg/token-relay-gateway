package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.ProviderKey;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderKeyRepository extends ReactiveCrudRepository<ProviderKey, UUID> {
  Flux<ProviderKey> findByStatusOrderByPriorityAsc(String status);

  Flux<ProviderKey> findByOwnerUserIdAndStatusOrderByPriorityAsc(UUID ownerUserId, String status);

  Flux<ProviderKey> findByOwnerUserIdIsNullAndStatusOrderByPriorityAsc(String status);

  Flux<ProviderKey> findByOwnerUserIdOrderByPriorityAsc(UUID ownerUserId);

  Mono<ProviderKey> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);
}
