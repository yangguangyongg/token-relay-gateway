package com.tokenrelay.gateway.adapter;

import com.tokenrelay.gateway.domain.ProviderKey;
import com.tokenrelay.gateway.proxy.GatewayRequest;
import com.tokenrelay.gateway.proxy.ProviderResponse;
import reactor.core.publisher.Mono;

public interface ProviderAdapter {
  boolean supports(ProviderKey key, GatewayRequest request);

  Mono<ProviderResponse> execute(ProviderKey key, GatewayRequest request);
}
