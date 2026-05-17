package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.ApiKeyRecord;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKeyRecord, UUID> {
  Mono<ApiKeyRecord> findByKeyHash(String keyHash);
  Flux<ApiKeyRecord> findByUserId(UUID userId);
}
