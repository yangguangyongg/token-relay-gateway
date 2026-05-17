package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.GatewayUser;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface GatewayUserRepository extends ReactiveCrudRepository<GatewayUser, UUID> {}
