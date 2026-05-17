package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.ProviderKey;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ProviderKeyRepository extends ReactiveCrudRepository<ProviderKey, UUID> {
  Flux<ProviderKey> findByStatusOrderByPriorityAsc(String status);
}
