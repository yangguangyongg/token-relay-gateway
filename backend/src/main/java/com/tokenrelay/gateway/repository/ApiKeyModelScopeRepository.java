package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.ApiKeyModelScope;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ApiKeyModelScopeRepository extends ReactiveCrudRepository<ApiKeyModelScope, UUID> {
  Flux<ApiKeyModelScope> findByApiKeyIdAndStatus(UUID apiKeyId, String status);
  Flux<ApiKeyModelScope> findByStatus(String status);
  Mono<Long> deleteByApiKeyId(UUID apiKeyId);
}
