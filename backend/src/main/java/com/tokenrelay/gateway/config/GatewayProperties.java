package com.tokenrelay.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
    String adminApiKey,
    String adminJwtSecret,
    long adminJwtTtlSeconds,
    String adminBootstrapAdminUsername,
    String adminBootstrapAdminPassword,
    String adminBootstrapViewerUsername,
    String adminBootstrapViewerPassword,
    List<String> adminIpWhitelist,
    List<String> allowedRegions,
    List<String> corsAllowedOrigins,
    long defaultProviderTimeoutSeconds,
    String providerKeyEncryptionKey) {

  public Duration providerTimeout() {
    return Duration.ofSeconds(defaultProviderTimeoutSeconds <= 0 ? 120 : defaultProviderTimeoutSeconds);
  }

  public Duration adminJwtTtl() {
    return Duration.ofSeconds(adminJwtTtlSeconds <= 0 ? 3600 : adminJwtTtlSeconds);
  }
}
