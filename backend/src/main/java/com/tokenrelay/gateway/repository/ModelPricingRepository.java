package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.ModelPricing;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ModelPricingRepository extends ReactiveCrudRepository<ModelPricing, UUID> {
  Flux<ModelPricing> findByStatusOrderByEffectiveFromDesc(String status);
}
