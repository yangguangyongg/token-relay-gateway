package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("api_keys")
public record ApiKeyRecord(
    @Id UUID id,
    UUID userId,
    String name,
    String keyPrefix,
    String keyHash,
    String status,
    int rateLimitPerMinute,
    Instant createdAt,
    Instant lastUsedAt) {}
