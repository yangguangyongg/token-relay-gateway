package com.tokenrelay.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
    String adminApiKey,
    List<String> allowedRegions,
    List<String> corsAllowedOrigins,
    long defaultProviderTimeoutSeconds) {

  public Duration providerTimeout() {
    return Duration.ofSeconds(defaultProviderTimeoutSeconds <= 0 ? 120 : defaultProviderTimeoutSeconds);
  }
}
