package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.GatewayUser;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface GatewayUserRepository extends ReactiveCrudRepository<GatewayUser, UUID> {
  Mono<GatewayUser> findByEmail(String email);
}
