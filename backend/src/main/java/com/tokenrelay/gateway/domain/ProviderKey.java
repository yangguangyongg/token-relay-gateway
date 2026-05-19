package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("provider_keys")
public record ProviderKey(
    @Id UUID id,
    UUID ownerUserId,
    String provider,
    String name,
    String baseUrl,
    String apiKey,
    String azureDeployment,
    String status,
    int priority,
    String healthStatus,
    Instant lastCheckedAt,
    String lastError,
    Instant createdAt,
    Instant updatedAt) {
  public ProviderKey withApiKey(String nextApiKey) {
    return new ProviderKey(
        id,
        ownerUserId,
        provider,
        name,
        baseUrl,
        nextApiKey,
        azureDeployment,
        status,
        priority,
        healthStatus,
        lastCheckedAt,
        lastError,
        createdAt,
        updatedAt);
  }
}
