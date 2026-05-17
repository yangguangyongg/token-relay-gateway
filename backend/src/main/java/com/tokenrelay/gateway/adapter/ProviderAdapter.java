package com.tokenrelay.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tokenrelay.gateway.domain.ProviderKey;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderAdapter {
  boolean supports(ProviderKey key, JsonNode request);

  Mono<ResponseEntity<Flux<String>>> stream(ProviderKey key, JsonNode request);
}
