package com.tokenrelay.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
    String adminApiKey,
    String adminJwtSecret,
    long adminJwtTtlSeconds,
    String workspaceJwtSecret,
    long workspaceJwtTtlSeconds,
    String adminBootstrapAdminUsername,
    String adminBootstrapAdminPassword,
    String adminBootstrapViewerUsername,
    String adminBootstrapViewerPassword,
    List<String> adminIpWhitelist,
    List<String> allowedRegions,
    List<String> corsAllowedOrigins,
    long defaultProviderTimeoutSeconds,
    int providerHttpMaxConnections,
    int providerHttpPendingAcquireMaxCount,
    long providerHttpPendingAcquireTimeoutMillis,
    int providerHttpConnectTimeoutMillis,
    long providerHttpIdleTimeoutSeconds,
    String providerKeyEncryptionKey) {

  public Duration providerTimeout() {
    return Duration.ofSeconds(defaultProviderTimeoutSeconds <= 0 ? 120 : defaultProviderTimeoutSeconds);
  }

  public Duration adminJwtTtl() {
    return Duration.ofSeconds(adminJwtTtlSeconds <= 0 ? 3600 : adminJwtTtlSeconds);
  }

  public Duration workspaceJwtTtl() {
    return Duration.ofSeconds(workspaceJwtTtlSeconds <= 0 ? 86400 : workspaceJwtTtlSeconds);
  }

  public int providerHttpMaxConnectionsSafe() {
    return providerHttpMaxConnections <= 0 ? 200 : providerHttpMaxConnections;
  }

  public int providerHttpPendingAcquireMaxCountSafe() {
    return providerHttpPendingAcquireMaxCount <= 0 ? 1000 : providerHttpPendingAcquireMaxCount;
  }

  public Duration providerHttpPendingAcquireTimeout() {
    return Duration.ofMillis(providerHttpPendingAcquireTimeoutMillis <= 0 ? 3000 : providerHttpPendingAcquireTimeoutMillis);
  }

  public int providerHttpConnectTimeoutMillisSafe() {
    return providerHttpConnectTimeoutMillis <= 0 ? 3000 : providerHttpConnectTimeoutMillis;
  }

  public Duration providerHttpIdleTimeout() {
    return Duration.ofSeconds(providerHttpIdleTimeoutSeconds <= 0 ? 60 : providerHttpIdleTimeoutSeconds);
  }
}
