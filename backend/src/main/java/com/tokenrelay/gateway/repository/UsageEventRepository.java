package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.UsageEvent;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface UsageEventRepository extends ReactiveCrudRepository<UsageEvent, UUID> {}
