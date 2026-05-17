package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.config.GatewayProperties;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
public class ComplianceService {
  private final Set<String> allowedRegions;

  public ComplianceService(GatewayProperties properties) {
    this.allowedRegions = properties.allowedRegions().stream()
        .map(region -> region.toUpperCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  public Mono<Void> check(ServerWebExchange exchange) {
    String region = exchange.getRequest().getHeaders().getFirst("X-Client-Region");
    if (region == null || region.isBlank()) {
      return Mono.error(new GatewayException(400, "missing_region", "X-Client-Region header is required"));
    }
    if (!allowedRegions.contains(region.toUpperCase(Locale.ROOT))) {
      return Mono.error(new GatewayException(403, "unsupported_region", "Client region is not allowed for this gateway"));
    }
    return Mono.empty();
  }
}
