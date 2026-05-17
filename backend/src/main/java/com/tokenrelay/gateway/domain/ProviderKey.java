package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("provider_keys")
public record ProviderKey(
    @Id UUID id,
    String provider,
    String name,
    String baseUrl,
    String apiKey,
    String azureDeployment,
    String status,
    int priority,
    Instant createdAt) {}
